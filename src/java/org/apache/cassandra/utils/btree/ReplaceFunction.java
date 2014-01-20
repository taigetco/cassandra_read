package org.apache.cassandra.utils.btree;

import com.google.common.base.Function;

/**
 * An interface defining a function to be applied to both the object we are replacing in a BTree and
 * the object that is intended to replace it, returning the object to actually replace it.
 *
 * If this is a new insertion, that is there is no object to replace, the one argument variant of
 * the function will be called.
 *
 * @param <V>
 */
public interface ReplaceFunction<V> extends Function<V, V>
{
    V apply(V replaced, V update);
}
