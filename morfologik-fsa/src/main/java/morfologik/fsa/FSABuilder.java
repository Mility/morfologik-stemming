package morfologik.fsa;

import java.util.*;

import morfologik.util.Arrays;

import static morfologik.fsa.ConstantArcSizeFSA.*;

/**
 * Fast, memory-conservative finite state automaton builder, returning a
 * byte-serialized {@link ConstantArcSizeFSA} (a tradeoff between construction
 * speed and memory consumption).
 */
public final class FSABuilder {
    /**
     * Debug and information constants.
     * 
     * @see FSABuilder#getInfo()
     */
    public enum InfoEntry {
        SERIALIZATION_BUFFER_SIZE("Serialization buffer size"),
        SERIALIZATION_BUFFER_REALLOCATIONS("Serialization buffer reallocs"),
        CONSTANT_ARC_AUTOMATON_SIZE("Constant arc FSA size"),
        MAX_ACTIVE_PATH_LENGTH("Max active path"),
        STATE_REGISTRY_TABLE_SLOTS("Registry hash slots"),
        STATE_REGISTRY_SIZE("Registry hash entries"),
        ESTIMATED_MEMORY_CONSUMPTION_MB("Estimated mem consumption (MB)");

        private final String stringified;
        
        InfoEntry(String stringified) {
            this.stringified = stringified;
        }

        @Override
        public String toString() {
            return stringified;
        }
    }

    /** A megabyte. */
    private final static int MB = 1024 * 1024;

    /**
     * Internal serialized FSA buffer expand ratio.
     */
    private final static int BUFFER_GROWTH_SIZE = 5 * MB;

    /**
     * Maximum number of labels from a single state.
     */
    private final static int MAX_LABELS = 256;

    /**
     * Comparator comparing full byte arrays consistently with 
     * {@link #compare(byte[], int, int, byte[], int, int)}.
     */
    public static final Comparator<byte[]> LEXICAL_ORDERING = new Comparator<byte[]>() {
        public int compare(byte[] o1, byte[] o2) {
            return FSABuilder.compare(o1, 0, o1.length, o2, 0, o2.length);
        }
    };

    /**
     * Internal serialized FSA buffer expand ratio.
     */
    private final int bufferGrowthSize;

    /**
     * Holds serialized and mutable states. Each state is a sequential list of
     * arcs, the last arc is marked with {@link #BIT_ARC_LAST}.
     */
    private byte[] serialized = new byte[0];

    /**
     * Number of bytes already taken in {@link #serialized}. Start from 1 to
     * keep 0 a sentinel value (for the hash set and final state).
     */
    private int size;

    /**
     * States on the "active path" (still mutable). Values are addresses of each
     * state's first arc.
     */
    private int[] activePath = new int[0];

    /**
     * Current length of the active path.
     */
    private int activePathLen;

    /**
     * The next offset at which an arc will be added to the given state on
     * {@link #activePath}.
     */
    private int[] nextArcOffset = new int[0];

    /**
     * Root state. If negative, the automaton has been built already and cannot be extended.
     */
    private int root;
    
    /**
     * An epsilon state. The first and only arc of this state points either 
     * to the root or to the terminal state, indicating an empty automaton. 
     */
    private int epsilon;

    /**
     * Hash set of state addresses in {@link #serialized}, hashed by
     * {@link #hash(int, int)}. Zero reserved for an unoccupied slot.
     */
    private int[] hashSet = new int[2];
    
    /**
     * Number of entries currently stored in {@link #hashSet}.
     */
    private int hashSize = 0;

    /**
     * Previous sequence added to the automaton in {@link #add(byte[], int, int)}. Used in assertions only.
     */
    private byte [] previous;

    /**
     * Information about the automaton and its compilation.
     */
    private TreeMap<InfoEntry, Object> info; 

    /**
     * {@link #previous} sequence's length, used in assertions only.
     */
    private int previousLength;

    /** */
    public FSABuilder() {
        this(BUFFER_GROWTH_SIZE);
    }
    
    /** */
    public FSABuilder(int bufferGrowthSize) {
        this.bufferGrowthSize = Math.max(bufferGrowthSize, ARC_SIZE * MAX_LABELS);

        // Allocate epsilon state.
        epsilon = allocateState(1);
        serialized[epsilon + FLAGS_OFFSET] |= BIT_ARC_LAST;

        // Allocate root, with an initial empty set of output arcs.
        expandActivePath(1);
        root = activePath[0];
    }

    /**
     * Add a single sequence of bytes to the FSA. The input must be lexicographically greater
     * than any previously added sequence.
     */
    public void add(byte[] sequence, int start, int len) {
        assert serialized != null : "Automaton already built.";
        assert previous == null || len == 0 || compare(previous, 0, previousLength, sequence, start, len) <= 0 : 
            "Input must be sorted: " 
                + Arrays.toString(previous, 0, previousLength) + " >= " 
                + Arrays.toString(sequence, start, len);
        assert setPrevious(sequence, start, len);
    
        // Determine common prefix length.
        final int commonPrefix = commonPrefix(sequence, start, len);
    
        // Make room for extra states on active path, if needed.
        expandActivePath(len);
    
        // Freeze all the states after the common prefix.
        for (int i = activePathLen - 1; i > commonPrefix; i--) {
            final int frozenState = freezeState(i);
            setArcTarget(nextArcOffset[i - 1] - ARC_SIZE, frozenState);
            nextArcOffset[i] = activePath[i];
        }

        // Create arcs to new suffix states.
        for (int i = commonPrefix + 1, j = start + commonPrefix; i <= len; i++) {
            final int p = nextArcOffset[i - 1];
    
            serialized[p + FLAGS_OFFSET] = (byte) (i == len ? BIT_ARC_FINAL : 0);
            serialized[p + LABEL_OFFSET] = sequence[j++];
            setArcTarget(p, i == len ? TERMINAL_STATE : activePath[i]);
    
            nextArcOffset[i - 1] = p + ARC_SIZE;
        }

        // Save last sequence's length so that we don't need to calculate it again.
        this.activePathLen = len;
    }

    /** Number of serialization buffer reallocations. */
    private int serializationBufferReallocations;
    
    /**
     * Complete the automaton.
     */
    public FSA complete() {
        add(new byte[0], 0, 0);
    
        if (nextArcOffset[0] - activePath[0] == 0) {
            // An empty FSA.
            setArcTarget(epsilon, TERMINAL_STATE);
        } else {
            // An automaton with at least a single arc from root.
            root = freezeState(0);
            setArcTarget(epsilon, root);
        }

        info = new TreeMap<InfoEntry, Object>();
        info.put(InfoEntry.SERIALIZATION_BUFFER_SIZE, serialized.length);
        info.put(InfoEntry.SERIALIZATION_BUFFER_REALLOCATIONS, serializationBufferReallocations);
        info.put(InfoEntry.CONSTANT_ARC_AUTOMATON_SIZE, size);
        info.put(InfoEntry.MAX_ACTIVE_PATH_LENGTH, activePath.length);
        info.put(InfoEntry.STATE_REGISTRY_TABLE_SLOTS, hashSet.length);
        info.put(InfoEntry.STATE_REGISTRY_SIZE, hashSize);
        info.put(InfoEntry.ESTIMATED_MEMORY_CONSUMPTION_MB, 
                (this.serialized.length + this.hashSet.length * 4) / (double) MB);

        final FSA fsa = new ConstantArcSizeFSA(java.util.Arrays.copyOf(this.serialized, this.size), epsilon);
        this.serialized = null;
        this.hashSet = null;
        return fsa;
    }

    /**
     * Build a minimal, deterministic automaton from a sorted list of byte sequences.
     */
    public static FSA build(byte[][] input) {
        final FSABuilder builder = new FSABuilder(); 
    
        for (byte [] chs : input)
            builder.add(chs, 0, chs.length);
    
        return builder.complete();
    }

    /**
     * Build a minimal, deterministic automaton from an iterable list of byte sequences.
     */
    public static FSA build(Iterable<byte[]> input) {
        final FSABuilder builder = new FSABuilder(); 
    
        for (byte [] chs : input)
            builder.add(chs, 0, chs.length);
    
        return builder.complete();
    }
    
    /**
     * Return various statistics concerning the FSA and its compilation.
     */
    public Map<InfoEntry, Object> getInfo() {
        return info;
    }
    
    /** Is this arc the state's last? */
    private boolean isArcLast(int arc) {
        return (serialized[arc + FLAGS_OFFSET] & BIT_ARC_LAST) != 0;
    }

    /** Is this arc final? */
    private boolean isArcFinal(int arc) {
        return (serialized[arc + FLAGS_OFFSET] & BIT_ARC_FINAL) != 0;
    }

    /** Get label's arc. */
    private byte getArcLabel(int arc) {
        return serialized[arc + LABEL_OFFSET];
    }

    /**
     * Fills the target state address of an arc.
     */
    private void setArcTarget(int arc, int state) {
        arc += ADDRESS_OFFSET + TARGET_ADDRESS_SIZE;
        for (int i = 0; i < TARGET_ADDRESS_SIZE; i++) {
            serialized[--arc] = (byte) state;
            state >>>= 8;
        }
    }

    /**
     * Returns the address of an arc.
     */
    private int getArcTarget(int arc) {
        arc += ADDRESS_OFFSET;
        return (serialized[arc]) << 24 | 
               (serialized[arc + 1] & 0xff) << 16 | 
               (serialized[arc + 2] & 0xff) << 8 |
               (serialized[arc + 3] & 0xff);
    }

    /**
     * @return The number of common prefix characters with the previous
     *         sequence.
     */
    private int commonPrefix(byte[] sequence, int start, int len) {
        // Empty root state case.
        final int max = Math.min(len, activePathLen);
        int i;
        for (i = 0; i < max; i++) {
            final int lastArc = nextArcOffset[i] - ARC_SIZE;
            if (sequence[start++] != getArcLabel(lastArc)) {
                break;
            }
        }

        return i;
    }

    /**
     * Freeze a state: try to find an equivalent state in the interned states
     * dictionary first, if found, return it, otherwise, serialize the mutable
     * state at <code>activePathIndex</code> and return it.
     */
    private int freezeState(final int activePathIndex) {
        final int start = activePath[activePathIndex];
        final int end = nextArcOffset[activePathIndex];
        final int len = end - start;

        // Set the last arc flag on the current active path's state.
        serialized[end - ARC_SIZE + FLAGS_OFFSET] |= BIT_ARC_LAST;

        // Try to locate a state with an identical content in the hash set.
        final int bucketMask = (hashSet.length - 1);
        int slot = hash(start, len) & bucketMask;
        for (int i = 0;;) {
            int state = hashSet[slot];
            if (state == 0) {
                state = hashSet[slot] = serialize(activePathIndex);
                if (++hashSize > hashSet.length / 2)
                    expandAndRehash();
                return state;
            } else if (equivalent(state, start, len)) {
                return state;
            }

            slot = (slot + (++i)) & bucketMask;
        }
    }

    /**
     * Reallocate and rehash the hash set.
     */
    private void expandAndRehash() {
        final int[] newHashSet = new int[hashSet.length * 2];
        final int bucketMask = (newHashSet.length - 1);

        for (int j = 0; j < hashSet.length; j++) {
            final int state = hashSet[j];
            if (state > 0) {
                int slot = hash(state, stateLength(state)) & bucketMask;
                for (int i = 0; newHashSet[slot] > 0;) {
                    slot = (slot + (++i)) & bucketMask;
                }
                newHashSet[slot] = state;
            }
        }
        this.hashSet = newHashSet;
    }

    /**
     * The total length of the serialized state data (all arcs). 
     */
    private int stateLength(int state) {
        int arc = state;
        while (!isArcLast(arc)) {
            arc += ARC_SIZE;
        }
        return arc - state + ARC_SIZE;
    }

    /** Return <code>true</code> if two regions in {@link #serialized} are identical. */
    private boolean equivalent(int start1, int start2, int len) {
        if (start1 + len > size || start2 + len > size)
            return false;

        while (len-- > 0)
            if (serialized[start1++] != serialized[start2++])
                return false;

        return true;
    }

    /**
     * Serialize a given state on the active path.
     */
    private int serialize(final int activePathIndex) {
        expandBuffers();

        final int newState = size;
        final int start = activePath[activePathIndex];
        final int len = nextArcOffset[activePathIndex] - start;
        System.arraycopy(serialized, start, serialized, newState, len);

        size += len;
        return newState;
    }

    /**
     * Hash code of a fragment of {@link #serialized} array.
     */
    private int hash(int start, int byteCount) {
        assert byteCount % ARC_SIZE == 0 : "Not an arc multiply?";

        int h = 0;
        for (int arcs = byteCount / ARC_SIZE; --arcs >= 0; start += ARC_SIZE) {
            h = 17 * h + getArcLabel(start);
            h = 17 * h + getArcTarget(start);
            if (isArcFinal(start)) h += 17;
        }

        return h;
    }

    /**
     * Append a new mutable state to the active path.
     */
    private void expandActivePath(int size) {
        if (activePath.length < size) {
            final int p = activePath.length;
            activePath = java.util.Arrays.copyOf(activePath, size);
            nextArcOffset = java.util.Arrays.copyOf(nextArcOffset, size);

            for (int i = p; i < size; i++) {
                nextArcOffset[i] = activePath[i] = 
                    allocateState(/* assume max labels count */ MAX_LABELS);
            }
        }
    }

    /**
     * Expand internal buffers for the next state.
     */
    private void expandBuffers() {
        if (this.serialized.length < size + ARC_SIZE * MAX_LABELS) {
            serialized = java.util.Arrays.copyOf(serialized, serialized.length + bufferGrowthSize);
            serializationBufferReallocations++;
        }
    }

    /**
     * Allocate space for a state with the given number of outgoing labels.
     * 
     * @return state offset
     */
    private int allocateState(int labels) {
        expandBuffers();
        final int state = size;
        size += labels * ARC_SIZE;
        return state;
    }
    
    /**
     * Copy <code>current</code> into an internal buffer.
     */
    private boolean setPrevious(byte [] sequence, int start, int length) {
        if (previous == null || previous.length < length) {
            previous = new byte [length];
        }

        System.arraycopy(sequence, start, previous, 0, length);
        previousLength = length;
        return true;
    }
    
    /**
     * Lexicographic order of input sequences. By default, consistent with the "C" sort
     * (absolute value of bytes, 0-255).
     */
    public static int compare(byte [] s1, int start1, int lens1, 
                              byte [] s2, int start2, int lens2) {
        final int max = Math.min(lens1, lens2);

        for (int i = 0; i < max; i++) {
            final byte c1 = s1[start1++];
            final byte c2 = s2[start2++];
            if (c1 != c2)
                return (c1 & 0xff) - (c2 & 0xff);
        }

        return lens1 - lens2;
    }
}
