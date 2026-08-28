package dev.modzuozhi.core.collection;

import it.unimi.dsi.fastutil.longs.AbstractLongSortedSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectSortedMap;
import it.unimi.dsi.fastutil.longs.LongBidirectionalIterator;
import it.unimi.dsi.fastutil.longs.LongComparator;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import it.unimi.dsi.fastutil.objects.AbstractObjectCollection;
import it.unimi.dsi.fastutil.objects.AbstractObjectSortedSet;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectIterators;
import it.unimi.dsi.fastutil.objects.ObjectSortedSet;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 并发的 {@code Long2ObjectLinkedOpenHashMap}（借鉴 MCMT/Async 思路，独立实现）。
 * <p>
 * {@code ChunkMap.updatingChunkMap/visibleChunkMap}（区块持有者核心表）字段类型是 fastutil
 * {@code Long2ObjectLinkedOpenHashMap}（属于 {@code Long2ObjectSortedMap}），mixin 无法改字段
 * 类型，只能替换<b>实例</b>。fastutil 无现成并发版，因此继承 {@link Long2ObjectLinkedOpenHashMap}
 * （保持字段类型兼容），内部用 {@link ConcurrentHashMap} 作为实际存储，覆盖 {@code ChunkMap}
 * 用到的 get/put/remove/containsKey/isEmpty/size/clear/values/keySet/long2ObjectEntrySet/clone。
 * <p>
 * 并发场景：维度 worker 的 {@code processUnloads/getChunks/acquireGeneration} 遍历/查询区块表，
 * 同时 {@code scheduleUnload} 的 {@code CompletableFuture} 异步回调（卸载调度）并发
 * {@code put/remove}——fastutil 链表哈希表结构被并发损坏（实测：服务器保存时
 * {@code Long2ObjectLinkedOpenHashMap.rehash} Index=-1 越界，区块保存失败）。
 * <p>
 * 迭代（{@link #values()}/{@link #keySet()}/{@link #long2ObjectEntrySet()}）基于
 * {@link ConcurrentHashMap} 的弱一致视图，并发增删不抛 CME/NPE。Sorted 特有的
 * firstLong/subSet/headSet/tailSet 等 {@code ChunkMap} 不使用，按未实现处理（抛
 * {@link UnsupportedOperationException}）；不再保持插入顺序（{@code ConcurrentHashMap}
 * 无序），区块遍历顺序可能变化，但不影响正确性。
 */
public final class ConcurrentLong2ObjectLinkedOpenHashMap<V>
        extends Long2ObjectLinkedOpenHashMap<V> {
    private static final long serialVersionUID = 1L;

    /** 实际存储：并发哈希表。 */
    private final Map<Long, V> backing = new ConcurrentHashMap<>();

    @Override
    public V get(long key) {
        V v = backing.get(key);
        return (v == null && !backing.containsKey(key)) ? defaultReturnValue() : v;
    }

    @Override
    public V get(Object key) {
        return (key instanceof Long) ? get((long) (Long) key) : defaultReturnValue();
    }

    @Override
    public boolean containsKey(long key) {
        return backing.containsKey(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof Long && backing.containsKey(key);
    }

    @Override
    public V put(long key, V value) {
        return backing.put(key, value);
    }

    @Override
    public V put(Long key, V value) {
        return backing.put(key, value);
    }

    @Override
    public V remove(long key) {
        return backing.remove(key);
    }

    @Override
    public V remove(Object key) {
        return backing.remove(key);
    }

    @Override
    public boolean remove(long key, Object value) {
        return backing.remove(key, value);
    }

    @Override
    public boolean remove(Object key, Object value) {
        return key instanceof Long && backing.remove(key, value);
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
    public V getOrDefault(long key, V defaultValue) {
        return backing.getOrDefault(key, defaultValue);
    }

    @Override
    public ObjectCollection<V> values() {
        return new ValuesView<>(backing.values());
    }

    @Override
    public LongSortedSet keySet() {
        return new KeySetView(backing.keySet());
    }

    @Override
    public Long2ObjectSortedMap.FastSortedEntrySet<V> long2ObjectEntrySet() {
        return new EntrySetView<>(backing.entrySet());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public ObjectSortedSet<Map.Entry<Long, V>> entrySet() {
        // Long2ObjectMap.Entry<V> extends Map.Entry<Long,V>，运行时元素兼容，cast 安全
        return (ObjectSortedSet) long2ObjectEntrySet();
    }

    /** 复制当前内容到新的并发实例（原 {@code updatingChunkMap.clone()} 用于生成
     *  {@code visibleChunkMap}；fastutil 父类 clone 只拷贝内部数组，在并发版中为空，必须重写）。 */
    @Override
    public ConcurrentLong2ObjectLinkedOpenHashMap<V> clone() {
        ConcurrentLong2ObjectLinkedOpenHashMap<V> c = new ConcurrentLong2ObjectLinkedOpenHashMap<>();
        c.backing.putAll(this.backing);
        return c;
    }

    /** 弱一致 {@code ObjectCollection} 视图。 */
    private static final class ValuesView<V> extends AbstractObjectCollection<V> {
        private static final long serialVersionUID = 1L;

        private final Collection<V> backing;

        ValuesView(Collection<V> backing) {
            this.backing = backing;
        }

        @Override
        public ObjectBidirectionalIterator<V> iterator() {
            return new ObjectBidiAdapter<>(backing.iterator());
        }

        @Override
        public int size() {
            return backing.size();
        }

        @Override
        public boolean contains(Object o) {
            return backing.contains(o);
        }

        @Override
        public boolean isEmpty() {
            return backing.isEmpty();
        }
    }

    /** 弱一致 {@code LongSortedSet} 视图（Sorted 特有方法未使用，按未实现处理）。 */
    private static final class KeySetView extends AbstractLongSortedSet {
        private static final long serialVersionUID = 1L;

        private final Set<Long> backing;

        KeySetView(Set<Long> backing) {
            this.backing = backing;
        }

        @Override
        public LongBidirectionalIterator iterator() {
            return new LongBidiAdapter(backing.iterator());
        }

        @Override
        public LongBidirectionalIterator iterator(long fromElement) {
            // 无序并发视图无法定位起始元素；未被 ChunkMap 使用，返回完整迭代器
            return iterator();
        }

        @Override
        public int size() {
            return backing.size();
        }

        @Override
        public boolean add(long k) {
            return backing.add(k);
        }

        @Override
        public boolean remove(long k) {
            return backing.remove(k);
        }

        @Override
        public boolean contains(long k) {
            return backing.contains(k);
        }

        @Override
        public boolean contains(Object o) {
            return backing.contains(o);
        }

        @Override
        public boolean remove(Object o) {
            return backing.remove(o);
        }

        @Override
        public void clear() {
            backing.clear();
        }

        @Override
        public LongComparator comparator() {
            return null;
        }

        @Override
        public LongSortedSet subSet(long from, long to) {
            throw new UnsupportedOperationException("concurrent view: no order");
        }

        @Override
        public LongSortedSet headSet(long to) {
            throw new UnsupportedOperationException("concurrent view: no order");
        }

        @Override
        public LongSortedSet tailSet(long from) {
            throw new UnsupportedOperationException("concurrent view: no order");
        }

        @Override
        public long firstLong() {
            throw new UnsupportedOperationException("concurrent view: no order");
        }

        @Override
        public long lastLong() {
            throw new UnsupportedOperationException("concurrent view: no order");
        }
    }

    /** 弱一致 {@code FastSortedEntrySet} 视图（Sorted 特有方法未使用，按未实现处理）。 */
    private static final class EntrySetView<V>
            extends AbstractObjectSortedSet<Long2ObjectMap.Entry<V>>
            implements Long2ObjectSortedMap.FastSortedEntrySet<V> {
        private static final long serialVersionUID = 1L;

        private final Set<Map.Entry<Long, V>> backing;

        EntrySetView(Set<Map.Entry<Long, V>> backing) {
            this.backing = backing;
        }

        @Override
        public ObjectBidirectionalIterator<Long2ObjectMap.Entry<V>> iterator() {
            java.util.Iterator<Map.Entry<Long, V>> it = backing.iterator();
            return new ObjectBidirectionalIterator<>() {
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public Long2ObjectMap.Entry<V> next() {
                    return new EntryAdapter<>(it.next());
                }

                @Override
                public boolean hasPrevious() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Long2ObjectMap.Entry<V> previous() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void remove() {
                    it.remove();
                }
            };
        }

        @Override
        public ObjectBidirectionalIterator<Long2ObjectMap.Entry<V>> iterator(Long2ObjectMap.Entry<V> fromElement) {
            return iterator(); // 无序并发视图无法定位起始元素；未被 ChunkMap 使用
        }

        @Override
        public ObjectBidirectionalIterator<Long2ObjectMap.Entry<V>> fastIterator() {
            return iterator();
        }

        @Override
        public ObjectBidirectionalIterator<Long2ObjectMap.Entry<V>> fastIterator(Long2ObjectMap.Entry<V> fromElement) {
            return iterator();
        }

        @Override
        public int size() {
            return backing.size();
        }

        @Override
        public boolean contains(Object o) {
            return backing.contains(o);
        }

        @Override
        public boolean isEmpty() {
            return backing.isEmpty();
        }

        @Override
        public java.util.Comparator<? super Long2ObjectMap.Entry<V>> comparator() {
            return null;
        }

        @Override
        public ObjectSortedSet<Long2ObjectMap.Entry<V>> subSet(
                Long2ObjectMap.Entry<V> from, Long2ObjectMap.Entry<V> to) {
            throw new UnsupportedOperationException("concurrent view: no order");
        }

        @Override
        public ObjectSortedSet<Long2ObjectMap.Entry<V>> headSet(Long2ObjectMap.Entry<V> to) {
            throw new UnsupportedOperationException("concurrent view: no order");
        }

        @Override
        public ObjectSortedSet<Long2ObjectMap.Entry<V>> tailSet(Long2ObjectMap.Entry<V> from) {
            throw new UnsupportedOperationException("concurrent view: no order");
        }

        @Override
        public Long2ObjectMap.Entry<V> first() {
            throw new UnsupportedOperationException("concurrent view: no order");
        }

        @Override
        public Long2ObjectMap.Entry<V> last() {
            throw new UnsupportedOperationException("concurrent view: no order");
        }
    }

    /** 把 {@code java.util.Iterator<Long>} 包装成 fastutil 双向迭代器（previous 未实现）。 */
    private static final class LongBidiAdapter implements LongBidirectionalIterator {
        private final java.util.Iterator<Long> delegate;

        LongBidiAdapter(java.util.Iterator<Long> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public long nextLong() {
            return delegate.next();
        }

        @Override
        public Long next() {
            return delegate.next();
        }

        @Override
        public boolean hasPrevious() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long previousLong() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long previous() {
            throw new UnsupportedOperationException();
        }
    }

    /** 把 {@code java.util.Iterator<T>} 包装成 fastutil 双向迭代器（previous 未实现）。 */
    private static final class ObjectBidiAdapter<T> implements ObjectBidirectionalIterator<T> {
        private final java.util.Iterator<T> delegate;

        ObjectBidiAdapter(java.util.Iterator<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public T next() {
            return delegate.next();
        }

        @Override
        public boolean hasPrevious() {
            throw new UnsupportedOperationException();
        }

        @Override
        public T previous() {
            throw new UnsupportedOperationException();
        }
    }

    /** 把 {@code java.util.Map.Entry} 适配为 fastutil {@code Long2ObjectMap.Entry}。 */
    private static final class EntryAdapter<V> implements Long2ObjectMap.Entry<V> {
        private final Map.Entry<Long, V> delegate;

        EntryAdapter(Map.Entry<Long, V> delegate) {
            this.delegate = delegate;
        }

        @Override
        public long getLongKey() {
            return delegate.getKey();
        }

        @Override
        public Long getKey() {
            return delegate.getKey();
        }

        @Override
        public V getValue() {
            return delegate.getValue();
        }

        @Override
        public V setValue(V value) {
            return delegate.setValue(value);
        }
    }
}
