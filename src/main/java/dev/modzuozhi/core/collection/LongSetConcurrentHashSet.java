package dev.modzuozhi.core.collection;

import it.unimi.dsi.fastutil.longs.LongIterators;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 并发的 {@code LongSet}（借鉴 MCMT/Async 思路，独立实现）。
 * <p>
 * {@code ChunkMap.toDrop}（待卸载区块集合）字段类型是 fastutil {@code LongSet}，mixin 无法改
 * 字段类型，只能替换<b>实例</b>。fastutil 无现成并发版，因此继承 {@link LongOpenHashSet}
 * （保持字段类型兼容），内部用 {@link ConcurrentHashMap} 作为实际存储，仅覆盖 {@code ChunkMap}
 * 用到的 add/remove/contains/isEmpty/size/clear/iterator 等方法。
 * <p>
 * {@link #iterator()} 返回 {@link ConcurrentHashMap#keySet()} 的弱一致迭代器：遍历期间并发
 * 增删不抛 CME/NPE。解决「维度 worker 的 {@code ChunkMap.processUnloads} 遍历 toDrop」与
 * 「区块卸载调度并发 add/remove」的竞态（实测：末地维度 tick 抛
 * {@code LongOpenHashSet$SetIterator} wrapped=null NPE）。
 */
public final class LongSetConcurrentHashSet extends LongOpenHashSet {
    private static final long serialVersionUID = 1L;

    /** 实际存储：并发哈希表。 */
    private final ConcurrentHashMap<Long, Boolean> backing = new ConcurrentHashMap<>();

    @Override
    public boolean add(long k) {
        return backing.put(k, Boolean.TRUE) == null;
    }

    @Override
    public boolean add(Long k) {
        return backing.put(k, Boolean.TRUE) == null;
    }

    @Override
    public boolean remove(long k) {
        return backing.remove(k) != null;
    }

    @Override
    public boolean remove(Object k) {
        return backing.remove(k) != null;
    }

    @Override
    public boolean contains(long k) {
        return backing.containsKey(k);
    }

    @Override
    public boolean contains(Object k) {
        return k instanceof Long && backing.containsKey(k);
    }

    @Override
    public boolean isEmpty() {
        return backing.isEmpty();
    }

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public void clear() {
        backing.clear();
    }

    @Override
    public LongIterator iterator() {
        // ConcurrentHashMap keySet 弱一致迭代：并发增删安全，remove 可用
        return LongIterators.asLongIterator(backing.keySet().iterator());
    }
}
