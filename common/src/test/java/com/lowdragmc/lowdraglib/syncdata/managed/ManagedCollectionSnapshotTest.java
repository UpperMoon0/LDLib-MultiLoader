package com.lowdragmc.lowdraglib.syncdata.managed;

import com.lowdragmc.lowdraglib.syncdata.SyncUtils;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

class ManagedCollectionSnapshotTest {
    private static class NoLiveIteratorList<T> extends ArrayList<T> {
        @Override
        public Iterator<T> iterator() {
            throw new ConcurrentModificationException("live iterator must not be used");
        }
    }

    private static final class FlakySnapshotList<T> extends NoLiveIteratorList<T> {
        boolean rejectSnapshot;

        @Override
        public Object[] toArray() {
            if (rejectSnapshot) {
                rejectSnapshot = false;
                throw new ConcurrentModificationException("collection is currently mutating");
            }
            return super.toArray();
        }
    }

    private static final class CollectionVar implements IManagedVar<Collection<String>> {
        Collection<String> value;

        CollectionVar(Collection<String> value) {
            this.value = value;
        }

        @Override
        public Collection<String> value() {
            return value;
        }

        @Override
        public void set(Collection<String> value) {
            this.value = value;
        }

        @Override
        public boolean isPrimitive() {
            return false;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override
        public Class<Collection<String>> getType() {
            return (Class) Collection.class;
        }
    }

    private ManagedArrayLikeRef createRef(Collection<String> values) throws Exception {
        var variable = new CollectionVar(values);
        var ref = new ManagedArrayLikeRef(variable, false);
        ref.setKey(new ManagedKey("value", true, true, false, false,
                Collection.class, CollectionVar.class.getDeclaredField("value")));
        return ref;
    }

    @Test
    void snapshotsWithoutLiveIterator() {
        var values = new NoLiveIteratorList<String>();
        values.add("one");
        values.add("two");
        assertArrayEquals(new Object[] {"one", "two"}, (Object[]) SyncUtils.copyArrayLike(values, false));
        var ref = assertDoesNotThrow(() -> createRef(values));
        values.set(0, "changed");
        assertDoesNotThrow(ref::update);
        assertTrue(ref.isSyncDirty());
    }

    @Test
    void nullableElementsAreComparedWithoutCrashingOrFalseDirty() throws Exception {
        var values = new NoLiveIteratorList<String>();
        values.add(null);
        var ref = createRef(values);

        assertFalse(SyncUtils.isChanged(null, null));
        assertTrue(SyncUtils.isChanged(null, "set"));
        assertTrue(SyncUtils.isChanged("set", null));
        assertDoesNotThrow(ref::update);
        assertFalse(ref.isSyncDirty(), "unchanged null is not dirty");

        values.set(0, "set");
        assertDoesNotThrow(ref::update);
        assertTrue(ref.isSyncDirty(), "null to value must be dirty");
        ref.clearSyncDirty();

        values.set(0, null);
        assertDoesNotThrow(ref::update);
        assertTrue(ref.isSyncDirty(), "value to null must be dirty");
        ref.clearSyncDirty();
        assertDoesNotThrow(ref::update);
        assertFalse(ref.isSyncDirty(), "stable null remains clean");
    }

    @Test
    void detectsSizeChangeAndRecoverableConcurrentMutation() throws Exception {
        var values = new FlakySnapshotList<String>();
        values.add("one");
        var ref = createRef(values);
        values.add("two");
        values.rejectSnapshot = true;
        assertDoesNotThrow(ref::update);
        assertFalse(ref.isSyncDirty(), "unstable read should be retried, not committed");
        assertDoesNotThrow(ref::update);
        assertTrue(ref.isSyncDirty(), "next successful snapshot must detect change");
    }

    @Test
    void nestedReadOnlyCollectionDoesNotUseLiveIterator() {
        var values = new NoLiveIteratorList<String>();
        values.add("one");
        var ref = new ReadonlyArrayRef(true, values);
        assertDoesNotThrow(ref::update);

        var temporarilyMutating = new FlakySnapshotList<String>();
        temporarilyMutating.add("one");
        var retryingRef = new ReadonlyArrayRef(true, temporarilyMutating);
        temporarilyMutating.rejectSnapshot = true;
        assertDoesNotThrow(retryingRef::update);
        assertDoesNotThrow(retryingRef::update);
    }
}
