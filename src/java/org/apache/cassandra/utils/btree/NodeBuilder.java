package org.apache.cassandra.utils.btree;

import java.util.Arrays;
import java.util.Comparator;

import static org.apache.cassandra.utils.btree.BTree.EMPTY_BRANCH;
import static org.apache.cassandra.utils.btree.BTree.FAN_FACTOR;
import static org.apache.cassandra.utils.btree.BTree.POSITIVE_INFINITY;
import static org.apache.cassandra.utils.btree.BTree.compare;
import static org.apache.cassandra.utils.btree.BTree.find;
import static org.apache.cassandra.utils.btree.BTree.getKeyEnd;
import static org.apache.cassandra.utils.btree.BTree.isLeaf;

/**
 * Represents a level / stack item of in progress modifications to a BTree.
 */
final class NodeBuilder
{
    private static final int MAX_KEYS = 1 + (FAN_FACTOR * 2);

    // parent stack
    private NodeBuilder parent, child;

    // buffer for building new nodes
    private Object[] buildKeys = new Object[MAX_KEYS];  // buffers keys for branches and leaves
    private Object[] buildChildren = new Object[1 + MAX_KEYS]; // buffers children for branches only
    private int buildKeyPosition;
    private int buildChildPosition;
    // we null out the contents of buildKeys/buildChildren when clear()ing them for re-use; this is where
    // we track how much we actually have to null out
    private int maxBuildKeyPosition;
    private int maxBuildChildPosition;

    // current node of the btree we're modifying/copying from
    private Object[] copyFrom;
    // the index of the first key in copyFrom that has not yet been copied into the build arrays
    private int copyFromKeyPosition;
    // the index of the first child node in copyFrom that has not yet been copied into the build arrays
    private int copyFromChildPosition;

    // upper bound of range owned by this level; lets us know if we need to ascend back up the tree
    // for the next key we update when bsearch gives an insertion point past the end of the values
    // in the current node
    private Object upperBound;

    // ensure we aren't referencing any garbage
    void clear()
    {
        NodeBuilder current = this;
        while (current != null)
        {
            if (current.upperBound != null)
            {
                current.reset(null, null);
                Arrays.fill(current.buildKeys, 0, current.maxBuildKeyPosition, null);
                Arrays.fill(current.buildChildren, 0, current.maxBuildChildPosition, null);
                current.maxBuildChildPosition = current.maxBuildKeyPosition = 0;
            }
            current = current.child;
        }
    }

    // reset counters/setup to copy from provided node
    void reset(Object[] copyFrom, Object upperBound)
    {
        this.copyFrom = copyFrom;
        this.upperBound = upperBound;
        maxBuildKeyPosition = Math.max(maxBuildKeyPosition, buildKeyPosition);
        maxBuildChildPosition = Math.max(maxBuildChildPosition, buildChildPosition);
        buildKeyPosition = 0;
        buildChildPosition = 0;
        copyFromKeyPosition = 0;
        copyFromChildPosition = 0;
    }

    /**
     * Inserts or replaces the provided key, copying all not-yet-visited keys prior to it into our buffer.
     *
     * @param key key we are inserting/replacing
     * @return the NodeBuilder to retry the update against (a child if we own the range being updated,
     * a parent if we do not -- we got here from an earlier key -- and we need to ascend back up),
     * or null if we finished the update in this node.
     */
    <V> NodeBuilder update(Object key, Comparator<V> comparator, ReplaceFunction<V> replaceF)
    {
        assert copyFrom != null;
        int copyFromKeyEnd = getKeyEnd(copyFrom);

        int i = find(comparator, (V) key, copyFrom, copyFromKeyPosition, copyFromKeyEnd);
        boolean found = i >= 0; // exact key match?
        boolean owns = true; // true iff this node (or a child) should contain the key
        if (!found)
        {
            i = -i - 1;
            if (i == copyFromKeyEnd && compare(comparator, upperBound, key) <= 0)
                owns = false;
        }

        if (isLeaf(copyFrom))
        {
            // copy keys from the original node up to prior to the found index
            copyKeys(i);

            if (owns)
            {
                if (found)
                    replaceNextKey(key, replaceF);
                else
                    addNewKey(key, replaceF); // handles splitting parent if necessary via ensureRoom

                // done, so return null
                return null;
            }

            // if we don't own it, all we need to do is ensure we've copied everything in this node
            // (which we have done, since not owning means pos >= keyEnd), ascend, and let Modifier.update
            // retry against the parent node.  The if/ascend after the else branch takes care of that.
        }
        else
        {
            // branch
            if (found)
            {
                copyKeys(i);
                replaceNextKey(key, replaceF);
                copyChildren(i + 1);
                return null;
            }
            else if (owns)
            {
                copyKeys(i);
                copyChildren(i);

                // belongs to the range owned by this node, but not equal to any key in the node
                // so descend into the owning child
                Object newUpperBound = i < copyFromKeyEnd ? copyFrom[i] : upperBound;
                Object[] descendInto = (Object[]) copyFrom[copyFromKeyEnd + i];
                ensureChild().reset(descendInto, newUpperBound);
                return child;
            }
            else
            {
                // ensure we've copied all keys and children
                copyKeys(copyFromKeyEnd);
                copyChildren(copyFromKeyEnd + 1); // since we know that there are exactly 1 more child nodes, than keys
            }
        }

        if (key == POSITIVE_INFINITY && isRoot())
            return null;

        return ascend();
    }


    // UTILITY METHODS FOR IMPLEMENTATION OF UPDATE/BUILD/DELETE

    boolean isRoot()
    {
        // if parent == null, or parent.upperBound == null, then we have not initialised a parent builder,
        // so we are the top level builder holding modifications; if we have more than FAN_FACTOR items, though,
        // we are not a valid root so we would need to spill-up to create a new root
        return (parent == null || parent.upperBound == null) && buildKeyPosition <= FAN_FACTOR;
    }

    // ascend to the root node, splitting into proper node sizes as we go; useful for building
    // where we work only on the newest child node, which may construct many spill-over parents as it goes
    NodeBuilder ascendToRoot()
    {
        NodeBuilder current = this;
        while (!current.isRoot())
            current = current.ascend();
        return current;
    }

    // builds a new root BTree node - must be called on root of operation
    Object[] toNode()
    {
        assert buildKeyPosition <= FAN_FACTOR && buildKeyPosition > 0 : buildKeyPosition;
        return buildFromRange(0, buildKeyPosition, isLeaf(copyFrom));
    }

    // finish up this level and pass any constructed children up to our parent, ensuring a parent exists
    private NodeBuilder ascend()
    {
        ensureParent();
        boolean isLeaf = isLeaf(copyFrom);
        if (buildKeyPosition > FAN_FACTOR)
        {
            // split current node and move the midpoint into parent, with the two halves as children
            int mid = buildKeyPosition / 2;
            parent.addExtraChild(buildFromRange(0, mid, isLeaf), buildKeys[mid]);
            parent.finishChild(buildFromRange(mid + 1, buildKeyPosition - (mid + 1), isLeaf));
        }
        else
        {
            parent.finishChild(buildFromRange(0, buildKeyPosition, isLeaf));
        }
        return parent;
    }

    // copy keys from copyf to the builder, up to the provided index in copyf (exclusive)
    private void copyKeys(int upToKeyPosition)
    {
        if (copyFromKeyPosition >= upToKeyPosition)
            return;

        int len = upToKeyPosition - copyFromKeyPosition;
        assert len <= FAN_FACTOR : upToKeyPosition + "," + copyFromKeyPosition;

        ensureRoom(buildKeyPosition + len);
        System.arraycopy(copyFrom, copyFromKeyPosition, buildKeys, buildKeyPosition, len);
        copyFromKeyPosition = upToKeyPosition;
        buildKeyPosition += len;
    }

    // skips the next key in copyf, and puts the provided key in the builder instead
    private <V> void replaceNextKey(Object with, ReplaceFunction<V> replaceF)
    {
        // (this first part differs from addNewKey in that we pass the replaced object to replaceF as well)
        ensureRoom(buildKeyPosition + 1);
        if (replaceF != null)
            with = replaceF.apply((V) copyFrom[copyFromKeyPosition], (V) with);
        buildKeys[buildKeyPosition++] = with;

        copyFromKeyPosition++;
    }

    // puts the provided key in the builder, with no impact on treatment of data from copyf
    <V> void addNewKey(Object key, ReplaceFunction<V> replaceF)
    {
        ensureRoom(buildKeyPosition + 1);
        if (replaceF != null)
            key = replaceF.apply((V) key);
        buildKeys[buildKeyPosition++] = key;
    }

    // copies children from copyf to the builder, up to the provided index in copyf (exclusive)
    private void copyChildren(int upToChildPosition)
    {
        // (ensureRoom isn't called here, as we should always be at/behind key additions)
        if (copyFromChildPosition >= upToChildPosition)
            return;
        int len = upToChildPosition - copyFromChildPosition;
        System.arraycopy(copyFrom, getKeyEnd(copyFrom) + copyFromChildPosition, buildChildren, buildChildPosition, len);
        copyFromChildPosition = upToChildPosition;
        buildChildPosition += len;
    }

    // adds a new and unexpected child to the builder - called by children that overflow
    private void addExtraChild(Object[] child, Object upperBound)
    {
        ensureRoom(buildKeyPosition + 1);
        buildKeys[buildKeyPosition++] = upperBound;
        buildChildren[buildChildPosition++] = child;
    }

    // adds a replacement expected child to the builder - called by children prior to ascending
    private void finishChild(Object[] child)
    {
        buildChildren[buildChildPosition++] = child;
        copyFromChildPosition++;
    }

    // checks if we can add the requested keys+children to the builder, and if not we spill-over into our parent
    private void ensureRoom(int nextBuildKeyPosition)
    {
        if (nextBuildKeyPosition < MAX_KEYS)
            return;

        // flush even number of items so we don't waste leaf space repeatedly
        Object[] flushUp = buildFromRange(0, FAN_FACTOR, isLeaf(copyFrom));
        ensureParent().addExtraChild(flushUp, buildKeys[FAN_FACTOR]);
        int size = FAN_FACTOR + 1;
        assert size <= buildKeyPosition : buildKeyPosition + "," + nextBuildKeyPosition;
        System.arraycopy(buildKeys, size, buildKeys, 0, buildKeyPosition - size);
        buildKeyPosition -= size;
        maxBuildKeyPosition = buildKeys.length;
        if (buildChildPosition > 0)
        {
            System.arraycopy(buildChildren, size, buildChildren, 0, buildChildPosition - size);
            buildChildPosition -= size;
            maxBuildChildPosition = buildChildren.length;
        }
    }

    // builds and returns a node from the buffered objects in the given range
    private Object[] buildFromRange(int offset, int keyLength, boolean isLeaf)
    {
        Object[] a;
        if (isLeaf)
        {
            a = new Object[keyLength + (keyLength & 1)];
            System.arraycopy(buildKeys, offset, a, 0, keyLength);
        }
        else
        {
            a = new Object[1 + (keyLength * 2)];
            System.arraycopy(buildKeys, offset, a, 0, keyLength);
            System.arraycopy(buildChildren, offset, a, keyLength, keyLength + 1);
        }
        return a;
    }

    // checks if there is an initialised parent, and if not creates/initialises one and returns it.
    // different to ensureChild, as we initialise here instead of caller, as parents in general should
    // already be initialised, and only aren't in the case where we are overflowing the original root node
    private NodeBuilder ensureParent()
    {
        if (parent == null)
        {
            parent = new NodeBuilder();
            parent.child = this;
        }
        if (parent.upperBound == null)
            parent.reset(EMPTY_BRANCH, upperBound);
        return parent;
    }

    // ensures a child level exists and returns it
    NodeBuilder ensureChild()
    {
        if (child == null)
        {
            child = new NodeBuilder();
            child.parent = this;
        }
        return child;
    }
}
