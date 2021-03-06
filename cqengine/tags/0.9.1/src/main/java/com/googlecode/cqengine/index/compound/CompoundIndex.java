/**
 * Copyright 2012 Niall Gallagher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.googlecode.cqengine.index.compound;

import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.index.common.AbstractMapBasedAttributeIndex;
import com.googlecode.cqengine.index.compound.impl.CompoundAttribute;
import com.googlecode.cqengine.index.compound.impl.CompoundQuery;
import com.googlecode.cqengine.index.compound.impl.CompoundValueTuple;
import com.googlecode.cqengine.quantizer.Quantizer;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.QueryOption;
import com.googlecode.cqengine.resultset.ResultSet;
import com.googlecode.cqengine.resultset.filter.QuantizedResultSet;
import com.googlecode.cqengine.resultset.stored.StoredResultSet;
import com.googlecode.cqengine.resultset.stored.StoredSetBasedResultSet;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * An index backed by a {@link java.util.concurrent.ConcurrentHashMap} which indexes {@link CompoundAttribute}s,
 * storing {@link CompoundValueTuple} objects as keys.
 * <p/>
 * Supports query types:
 * <ul>
 *     <li>
 *         {@link com.googlecode.cqengine.index.compound.impl.CompoundQuery}
 *     </li>
 * </ul>
 *
 * @author Niall Gallagher
 */
public class CompoundIndex<O> extends AbstractMapBasedAttributeIndex<CompoundValueTuple<O>, O> {

    private static final int INDEX_RETRIEVAL_COST = 20;

    private final CompoundAttribute<O> attribute;
    private final ConcurrentMap<CompoundValueTuple<O>, StoredResultSet<O>> indexMap = new ConcurrentHashMap<CompoundValueTuple<O>, StoredResultSet<O>>();

    /**
     * Package-private constructor, used by static factory methods. Creates a new HashIndex initialized to index the
     * supplied attribute.
     *
     * @param attribute The attribute on which the index will be built
     */
    CompoundIndex(CompoundAttribute<O> attribute) {
        // We can supply the superclass constructor with an empty set of supported queries,
        // because we implement/override supportsQuery() in this class instead...
        super(attribute, Collections.<Class<? extends Query>>emptySet());
        this.attribute = attribute;
    }

    /**
     * Returns true if the given {@link Query} is based on the same list of attributes as the
     * {@link CompoundAttribute} on which this compound index is based.
     *
     * @param query A {@link Query} to test
     * @return True if the given {@link Query} is based on the same list of attributes as the
     * {@link CompoundAttribute} on which this compound index is based, otherwise false
     */
    @Override
    public boolean supportsQuery(Query<O> query) {
        if (query instanceof CompoundQuery) {
            CompoundQuery<O> compoundQuery = (CompoundQuery<O>) query;
            return attribute.equals(compoundQuery.getCompoundAttribute());
        }
        return false;
    }

    @Override
    public CompoundAttribute<O> getAttribute() {
        return attribute;
    }

    /**
     * {@inheritDoc}
     * <p/>
     * This index is mutable.
     *
     * @return true
     */
    @Override
    public boolean isMutable() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ResultSet<O> retrieve(Query<O> query, Map<Class<? extends QueryOption>, QueryOption<O>> queryOptions) {
        Class<?> queryClass = query.getClass();
        if (queryClass.equals(CompoundQuery.class)) {
            final CompoundQuery<O> compoundQuery = (CompoundQuery<O>) query;
            final CompoundValueTuple<O> valueTuple = compoundQuery.getCompoundValueTuple();
            return new ResultSet<O>() {
                @Override
                public Iterator<O> iterator() {
                    ResultSet<O> rs = indexMap.get(getQuantizedValue(valueTuple));
                    return rs == null ? Collections.<O>emptySet().iterator() : filterForQuantization(rs, compoundQuery).iterator();
                }
                @Override
                public boolean contains(O object) {
                    ResultSet<O> rs = indexMap.get(getQuantizedValue(valueTuple));
                    return rs != null && filterForQuantization(rs, compoundQuery).contains(object);
                }
                @Override
                public int size() {
                    ResultSet<O> rs = indexMap.get(getQuantizedValue(valueTuple));
                    return rs == null ? 0 : filterForQuantization(rs, compoundQuery).size();
                }
                @Override
                public int getRetrievalCost() {
                    return INDEX_RETRIEVAL_COST;
                }
                @Override
                public int getMergeCost() {
                    // Return size of entire stored set as merge cost...
                    ResultSet<O> rs = indexMap.get(getQuantizedValue(valueTuple));
                    return rs == null ? 0 : rs.size();
                }
            };
        }
        else {
            throw new IllegalArgumentException("Unsupported query: " + query);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ConcurrentMap<CompoundValueTuple<O>, StoredResultSet<O>> getIndexMap() {
        return indexMap;
    }

    /**
     * {@inheritDoc}
     * @return A {@link StoredSetBasedResultSet} based on a set backed by {@link ConcurrentHashMap}, as created via
     * {@link Collections#newSetFromMap(java.util.Map)}
     */
    public StoredResultSet<O> createValueSet() {
        return new StoredSetBasedResultSet<O>(Collections.<O>newSetFromMap(new ConcurrentHashMap<O, Boolean>()));
    }

    // ---------- Hook methods which can be overridden by subclasses using a Quantizer ----------

    /**
     * A no-op method which can be overridden by a subclass to return a {@link ResultSet} which filters objects from the
     * given {@link ResultSet}, to return only those objects matching the query, for the case that the index is using a
     * {@link com.googlecode.cqengine.quantizer.Quantizer}.
     * <p/>
     * <b>This default implementation simply returns the given {@link ResultSet} unmodified.</b>
     *
     * @param storedResultSet A {@link ResultSet} stored against a quantized key in the index
     * @param query The query against which results should be matched
     * @return A {@link ResultSet} which filters objects from the given {@link ResultSet},
     * to return only those objects matching the query
     */
    protected ResultSet<O> filterForQuantization(ResultSet<O> storedResultSet, Query<O> query) {
        return storedResultSet;
    }

    /**
     * Creates a new {@link CompoundIndex} on the given combination of attributes.
     * <p/>
     * @param attributes The combination of simple attributes on which index will be built
     * @param <O> The type of the object containing the attributes
     * @return A {@link CompoundIndex} based on these attributes
     */
    public static <O> CompoundIndex<O> onAttributes(Attribute<O, ?>... attributes) {
        List<Attribute<O, ?>> attributeList = Arrays.asList(attributes);
        CompoundAttribute<O> compoundAttribute = new CompoundAttribute<O>(attributeList);
        return new CompoundIndex<O>(compoundAttribute);
    }

    /**
     * Creates a new {@link CompoundIndex} using the given {@link Quantizer} on the given combination of attributes.
     * <p/>
     * @param attributes The combination of simple attributes on which index will be built
     * @param quantizer A {@link Quantizer} to use in this index
     * @param <O> The type of the object containing the attributes
     * @return A {@link CompoundIndex} based on these attributes
     */
    public static <O> CompoundIndex<O> withQuantizerOnAttributes(final Quantizer<CompoundValueTuple<O>> quantizer, Attribute<O, ?>... attributes) {
        List<Attribute<O, ?>> attributeList = Arrays.asList(attributes);
        CompoundAttribute<O> compoundAttribute = new CompoundAttribute<O>(attributeList);
        return new CompoundIndex<O>(compoundAttribute) {

            // ---------- Override the hook methods related to Quantizer ----------

            final CompoundIndex<O> thisRef = this;
            @Override
            protected ResultSet<O> filterForQuantization(ResultSet<O> resultSet, final Query<O> query) {
                return new QuantizedResultSet<O>(resultSet, query);
            }

            @Override
            protected CompoundValueTuple<O> getQuantizedValue(CompoundValueTuple<O> attributeValue) {
                return quantizer.getQuantizedValue(attributeValue);
            }
        };
    }
}
