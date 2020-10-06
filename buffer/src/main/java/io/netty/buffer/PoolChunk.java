/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 *
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 *
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 *
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 *
 * depth=maxOrder is the last level and the leafs consist of pages
 *
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 *
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 *   memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 *   is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 *
 *  As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 *
 * Initialization -
 *   In the beginning we construct the memoryMap array by storing the depth of a node at each node
 *     i.e., memoryMap[id] = depth_of_id
 *
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 *                                    some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 *                                    is thus marked as unusable
 *
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 *    note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 *
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information depth_of_id and x as two byte values in memoryMap and depthMap respectively
 *
 * memoryMap[id]= depth_of_id  is defined above
 * depthMap[id]= x  indicates that the first node which is free to be allocated is at depth x (from root)
 */
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;
    /**
     * <note>
     *     PoolArena所代表的抽象就是netty内存池.
     *     这个属性用于记录当前PoolChunk属于哪个内存池.
     * </note>
     */
    final PoolArena<T> arena;
    /**
     * <note>
     *      PoolChunk声请的内存形式.
     *          对于堆内存，T就是一个byte数组
     *          对于直接内存，T就是ByteBuffer，
     *      无论是哪种形式，PoolChunk内存大小都默认是16M
     * </note>
     */
    final T memory;
    /**
     * <note>
     *     指定当前PoolChunk是否使用内存池的方式进行管理.
     *     PoolChunk是Netty声请的内存块Chunk的抽象,它既可以被内存池PoolArena管理,也可以独立使用不适用内存池管理
     * </note>
     */
    final boolean unpooled;
    /**
     * <note>
     *     PoolChunk内存块中声请用于站位，PoolChunk代表的整个内存块大小为16M+offset.
     *     默认该值为0
     * </note>
     */
    final int offset;
    /**
     * <note>
     *     PoolChunk会被维护成完全二叉树形式,memoryMap用于存储PoolChunk每个节点
     *     的以树的层高为值的内存分配情况,比如0=16M,1=8M等等以此类推,完全二叉树节点
     *     表示的存储容量是子节点之和.
     *     与depthMap不同的是,memoryMap是用于存储PoolChunk当前的内存分配情况,它
     *     在初始化时会与depthMap相等,随着PoolChunk的内存被分配,memoryMap所维护
     *     的"剩余容量"完全二叉树的节点值将会改变.
     *
     *     在进行内存分配时，会从头结点开始比较，然后比较左子节点，然后比较右
     *     子节点，直到找到能够代表目标内存块的节点。
     *     当某个节点所代表的内存被申请之后，该节点的值就会被标记为12(大于默认最大层高11)，
     *     表示该节点已经被占用
     * </note>
     */
    private final byte[] memoryMap;
    /**
     * <note>
     *     PoolChunk会被维护成完全二叉树形式,depthMap用于存储完全二叉树中
     *     所有节点的初始化层高,默认情况下PoolChunk会声请16M内存,所以
     *     depthMap.length=4096.depthMap初始化完毕后将不会再更改.
     *     它的主要作用在于通过目标索引位置值找到其在整棵树中的标准层数(初始化未被分配过的层数)
     * </note>
     */
    private final byte[] depthMap;
    /**
     * <note>
     *    每一个PoolSubPage都代表二叉树的一个叶子节点，当二叉树叶节点
     *    内存被单独分配之后(申请小于等于8kb的情况)，会使用一个PoolSubPage进行封装
     * </note>
     */
    private final PoolSubpage<T>[] subpages;
    /**
     * <note>
     *     用于判断用户声请的内存大小的值是否大于等于pageSize(默认为8KB=8192B).
     *     subpageOverflowMask的值为-8192，二进制表示为11111111111111111110000000000000，0的个数正好为12,
     *     而2^12=8192，所以Netty将其与申请的内存大小进行“与运算“，如果结果不为0则表示申请的内存大于8192，从而
     *     快速判断出通过PoolSubPage的方式声请内存还是通过内存计算的方式。
     * </note>
     */
    private final int subpageOverflowMask;
    /**
     * <note>
     *    记录叶节点大小，默认为8KB=8192B
     * </note>
     */
    private final int pageSize;
    /**
     * <note>
     *    叶节点偏移量，默认为13。主要用于待声请的内存大小可以在内存池的哪一层被声请到，算法为：
     *    int d = maxOrder - (log2(normCapacity) - pageShifts);
     *    比如9KB，经过log2(9KB)得到14，maxOrder为11，计算就得到10，表示9KB内存在内存池中为第10层的数据
     * </note>
     */
    private final int pageShifts;
    /**
     * <note>
     *    默认为11，表示当前最大层数
     * </note>
     */
    private final int maxOrder;
    /**
     * <note>
     *    记录当前整个PoolChunk申请的内存大小，默认为16M
     * </note>
     */
    private final int chunkSize;
    /**
     * <note>
     *    将chunkSize取2的对数，默认为24
     * </note>
     */
    private final int log2ChunkSize;
    /**
     * <note>
     *    代表叶节点的PoolSubPage数组的初始化length
     * </note>
     */
    private final int maxSubpageAllocs;
    /** Used to mark memory as unusable */
    /**
     * <note>
     *   指定某个节点如果已经被申请，那么其值将被标记为unusable所指定的值.比如说12
     * </note>
     */
    private final byte unusable;
    /**
     *  Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
     *  around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
     *  may produce extra GC, which can be greatly reduced by caching the duplicates.
     *  This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
     * <note>
     *    用于缓存已创建的ByteBuffer.Netty中所有的内存都是以ByteBuffer进行传递的。
     *    在分配内存时，Netty会将分配到的内存的起始值和偏移量使用ByteBuffer封装，交给用户使用。
     *    这也意味着申请多次内存，只要将同一个ByteBuffer中的数据进行重置，就可以复用这个ByteBuffer。
     *    cachedNioBuffers的作用就是缓存这些ByteBuffer对象，减少ByteBuffer的重复创建.
     * </note>
     */
    private final Deque<ByteBuffer> cachedNioBuffers;
    /**
     * <note>
     *     记录当前PoolChunk中还剩余的可申请字节数
     * </note>
     */
    private int freeBytes;
    /**
     * <note>
     *     Netty内存池中,所有的PoolChunk都是由内存池中的PoolChunkList统一维护,是一个双向链表.
     *     这个引用指向的是内存池中维护当前PoolChunk的链表
     * </note>
     */
    PoolChunkList<T> parent;
    /**
     * <note>
     *     前置节点PoolChunk
     * </note>
     */
    PoolChunk<T> prev;
    /**
     * <note>
     *     后置节点PoolChunk
     * </note>
     */
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        unpooled = false;
        this.arena = arena;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.maxOrder = maxOrder;
        this.chunkSize = chunkSize;
        this.offset = offset;
        unusable = (byte) (maxOrder + 1);
        log2ChunkSize = log2(chunkSize);
        subpageOverflowMask = ~(pageSize - 1);
        freeBytes = chunkSize;

        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder;

        // Generate the memory map.
        memoryMap = new byte[maxSubpageAllocs << 1];
        depthMap = new byte[memoryMap.length];
        int memoryMapIndex = 1;
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time
            int depth = 1 << d;
            for (int p = 0; p < depth; ++ p) {
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex ++;
            }
        }

        subpages = newSubpageArray(maxSubpageAllocs);
        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
        cachedNioBuffers = null;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    /**
     * 执行内存分配
     * @param buf  用于封装分配成功的内存
     * @param reqCapacity  请求声请的内存容量
     * @param normCapacity  规整后的内存容量,内存声请的容量会被规整为向上取整到2的幂次
     * @return  是否分配成功
     */
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        final long handle;
        /**
         * <note>
         *   subpageOverflowMask=-8192，用于判断待声请容量是否大于8KB。如果结果不为0则表示申请的内存大于8KB.
         *   返回的long型的handle，它由两部分组成：
         *      低位4个字节表示normCapacity分配的内存在PoolChunk中所分配的节点在memoryMap数组中的下标索引；
         *      高位4个字节表示分配的内存在PoolSubPage中占据的8KB内存中的BitMap索引，即分配的内存占用PoolSubPage中的哪一部分的内存。
         *   1、对于大于8KB的内存分配会直接在PoolChunk的二叉树上进行分配,即会分配多个PoolSubPage,所以高位四个字节的位图索引为0。
         *   低位的4个字节仍然表示目标内存节点在memoryMap中的位置索引；
         *   2、对于低于8KB的内存分配只占用PoolSubPage的一部分，所以需要使用BitMap索引来标识目标内存
         * </note>
         */
        if ((normCapacity & subpageOverflowMask) != 0) {
            /**
             * <note>
             *      申请大于8KB的内存。将会从MemoryMap所代表的二叉树中搜索节点，如果找到了能够
             *      分配的节点则会把二叉树中找到的节点标记为已占用。
             *      如果当前PoolChunk不足以分配待声请的内存大小，那么将会返回-1
             *      如果可以分配足够的大小，则返回分配到的memoryMap中节点的索引值
             * </note>
             *
             */
            handle = allocateRun(normCapacity);
        } else {
            // 申请小于等于8KB的内存
            handle = allocateSubpage(normCapacity);
        }

        /**
         * <note>
         *     如果handle小于0，则表示要申请的内存大小超过了当前PoolChunk剩余容量的最大内存大小,也即16M，
         *     所以返回false，不由当前PoolChunk进行分配。
         * </note>
         */
        if (handle < 0) {
            return false;
        }

        // 从缓存的ByteBuf对象池中获取一个ByteBuf对象，不存在则复制null.
        ByteBuffer nioBuffer = cachedNioBuffers != null ? cachedNioBuffers.pollLast() : null;
        /**
         * 通过申请到的内存数据handle,对nioBuffer进行重置，如果nioBuffer为null，则创建一个新的然后进行初始化。
         * 本质上就是首先计算申请到的内存块的起始位置地址值，以及申请的内存块的长度，然后将其设置到一个ByteBuf对象中,以对其进行重置.
         */
        initBuf(buf, nioBuffer, handle, reqCapacity);
        return true;
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     */
    private void updateParentsAlloc(int id) {
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            byte val = val1 < val2 ? val1 : val2;
            setValue(parentId, val);
            id = parentId;
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        int logChild = depth(id) + 1;
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     * <trans>
     *     从目标层数d开始，在memoryMap中查找并分配出一个可用node的算法.
     * </trans>
     * <note>
     *     算法的逻辑为：从父节点、左子节点、右子节点的方式依次判断节点的当前层数(MemoryMap)是否与目标层数相等，
     *     如果相等，则将该节点所对应的在memoryMap数组中的位置索引返回
     * </note>
     *
     * @param d depth   目标层数
     * @return index in memoryMap
     *      若找到了与目标层数d相等的节点，则返回节点在memoryMap中的索引
     *      若当前PoolChunk不足以分配d(找不到d)，将返回-1
     */
    private int allocateNode(int d) {
        int id = 1;   // 起点为1
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1
        // 获取memoryMap中索引为id上的层数值，初始时获取的就是根节点的层数
        byte val = value(id);
        // 如果d小于根节点层数值，说明当前PoolChunk中没有足够的内存用于分配目标内存，直接返回-1
        if (val > d) { // unusable
            return -1;
        }

        /**
         * 比较树节点的值是否比待分配目标节点的值要小，如果要小，则说明当前节点所代表的子树
         * 是能够分配目标内存大小的，则会继续遍历其左子节点，然后遍历右子节点
         */
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            id <<= 1;
            val = value(id);
            if (val > d) {
                /**
                 * val > d 表示当前节点的数值比目标数值要大，也就是说当前节点的容量不足以申请到目标容量的内存，
                 * 那么就会执行 id ^= 1，即将id切换到当前节点的兄弟节点。
                 * 本质上其实就是从二叉树的左子节点开始查找，如果左子节点无法分配
                 * 目标大小的内存，那么就到右子节点进行查找
                 */
                id ^= 1;
                val = value(id);
            }
        }

        // 跳出循环意味着已经找到了目标节点的索引(id)，则获取节点所在层数
        byte value = value(id);
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
        // 将id标记为已被占用
        setValue(id, unusable); // mark as unusable
        /**
         * 更新整棵树节点的值，使其继续保持
         * ”父节点的层数所代表的内存大小是未分配的子节点的层数所代表的内存之和“。
         */
        updateParentsAlloc(id);
        return id;
    }

    /**
     * Allocate a run of pages (>=1)
     * <trans>
     *     申请大于或等于1个page的内存容量
     * </trans>
     *
     * @param normCapacity normalized capacity  规整后的容量
     * @return index in memoryMap
     *      申请的容量在memoryMap中的索引位置。如果当前PoolChunk无法分配足够的内存，则返回-1
     */
    private long allocateRun(int normCapacity) {
        /**
         * log2(normCapacity)：会将申请的内存大小转换为大于该大小的第一个2的指数次幂数然后取2的对数的形式，比如log2(9KB)转换之后为14，这是因为大于9KB的第一个2的指数
         * 次幂为16384，取2的对数后为14。
         * 计算申请的目标内存（normCapacity）可以被申请到的在二叉树中需要的层数。
         */
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        /**
         * 从MemoryMap所代表的二叉树中搜索节点，如果找到了与目标层数相等的节点，则会标记节点为已占用，并返回索引值。
         * 如果当前PoolChunk不足以分配待声请的内存大小，那么将会返回-1
         */
        int id = allocateNode(d);
        /**
         * 如果返回值小于0，则说明当前PoolChunk中无法分配目标大小的内存.
         *      1、由于目标内存大于16M
         *      2、当前PoolChunk剩余可分配的内存不足以分配目标内存大小
         */
        if (id < 0) {
            return id;
        }
        // 更新剩余可分配内存的值
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created / initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        /**
         * // 这里其实也是与PoolThreadCache中存储PoolSubpage的方式相同，也是采用分层的方式进行存储的，
         * 取目标数组中哪一个元素的PoolSubpage则是根据目标容量normCapacity来进行的。
         * 查找当前申请的容量所适用的PoolSubpage.
         *
         */
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        // 赋值d为memoryMap的最大层数，它的目的是为了分配一个最小单位的内存块page.
        int d = maxOrder; // subpages are only be allocated from pages i.e., leaves
        synchronized (head) {
            // 从memoryMap中分配出一个最小内存块
            int id = allocateNode(d);
            // 如果无法分配成功，则返回id，即-1
            if (id < 0) {
                return id;
            }

            final PoolSubpage<T>[] subpages = this.subpages;
            final int pageSize = this.pageSize;
            // 计算当前PoolChunk剩余内存大小
            freeBytes -= pageSize;
            // 计算分配的id索引在PoolSubpage数组中的位置
            int subpageIdx = subpageIdx(id);
            PoolSubpage<T> subpage = subpages[subpageIdx];
            // 如果对应的索引为上没有subpage
            if (subpage == null) {
                // 创建一个PoolSubpage并交给subpages管理
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                subpage.init(head, normCapacity);
            }

            // 在PoolSubpage申请一块内存，并且返回代表该内存块的位图索引
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     * <trans>
     *     判断其是否小于8KB，如果低于8KB，则将其交由PoolSubpage进行处理，否则就通过二叉树的方式对其进行重置。
     * </trans>
     *
     * @param handle handle to free
     */
    void free(long handle, ByteBuffer nioBuffer) {
        // 获取handle代表的内存块在memoryMap数组中的位置
        int memoryMapIdx = memoryMapIdx(handle);
        // 获取handle代表的当前内存块的位图索引
        int bitmapIdx = bitmapIdx(handle);

        // 如果存在位图索引，意味着这个内存块是由PoolSubPage进行管理的，那么就释放SubPage中的内存块
        if (bitmapIdx != 0) {
            // 获取到subPage
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // 释放内存
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            synchronized (head) {
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
            }
        }

        // 走到这里说明需要释放的内存大于8KB，不是由SubPage管理，而是直接在memoryMap上进行分配的
        // 首先还原当前剩余的内存大小
        freeBytes += runLength(memoryMapIdx);
        // 重置释放内存块的二叉树节点
        setValue(memoryMapIdx, depth(memoryMapIdx));
        // 将要释放的内存块所对应的二叉树的各级父节点的值进行更新
        updateParentsFree(memoryMapIdx);

        // 将创建的ByteBuf对象释放到缓存池中，以便下次申请时复用
        if (nioBuffer != null && cachedNioBuffers != null &&
                cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle);
        if (bitmapIdx == 0) {
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            buf.init(this, nioBuffer, handle, runOffset(memoryMapIdx) + offset,
                    reqCapacity, runLength(memoryMapIdx), arena.parent.threadCache());
        } else {
            initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx, reqCapacity);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx(handle), reqCapacity);
    }

    private void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer,
                                    long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        int memoryMapIdx = memoryMapIdx(handle);

        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        buf.init(
            this, nioBuffer, handle,
            runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        return 1 << log2ChunkSize - depth(id);
    }

    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        int shift = id ^ 1 << depth(id);
        return shift * runLength(id);
    }

    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
