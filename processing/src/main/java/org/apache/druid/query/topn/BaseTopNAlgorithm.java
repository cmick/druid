/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.query.topn;

import com.google.common.annotations.VisibleForTesting;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.query.aggregation.Aggregator;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.BufferAggregator;
import org.apache.druid.segment.Cursor;
import org.apache.druid.segment.DimensionSelector;
import org.apache.druid.segment.IdLookup;
import org.apache.druid.segment.TopNOptimizationInspector;
import org.apache.druid.segment.column.ColumnCapabilities;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 */
public abstract class BaseTopNAlgorithm<DimValSelector, DimValAggregateStore, Parameters extends TopNParams>
    implements TopNAlgorithm<DimValSelector, Parameters>
{
  public static Aggregator[] makeAggregators(Cursor cursor, List<AggregatorFactory> aggregatorSpecs)
  {
    Aggregator[] aggregators = new Aggregator[aggregatorSpecs.size()];
    int aggregatorIndex = 0;
    for (AggregatorFactory spec : aggregatorSpecs) {
      aggregators[aggregatorIndex] = spec.factorize(cursor.getColumnSelectorFactory());
      ++aggregatorIndex;
    }
    return aggregators;
  }

  protected static BufferAggregator[] makeBufferAggregators(Cursor cursor, List<AggregatorFactory> aggregatorSpecs)
  {
    BufferAggregator[] aggregators = new BufferAggregator[aggregatorSpecs.size()];
    int aggregatorIndex = 0;
    for (AggregatorFactory spec : aggregatorSpecs) {
      aggregators[aggregatorIndex] = spec.factorizeBuffered(cursor.getColumnSelectorFactory());
      ++aggregatorIndex;
    }
    return aggregators;
  }

  protected TopNCursorInspector cursorInspector;

  protected BaseTopNAlgorithm(TopNCursorInspector cursorInspector)
  {
    this.cursorInspector = cursorInspector;
  }

  @Override
  public void run(
      Parameters params,
      TopNResultBuilder resultBuilder,
      DimValSelector dimValSelector,
      @Nullable TopNQueryMetrics queryMetrics
  )
  {
    if (params.getCardinality() != TopNParams.CARDINALITY_UNKNOWN) {
      runWithCardinalityKnown(params, resultBuilder, dimValSelector, queryMetrics);
    } else {
      runWithCardinalityUnknown(params, resultBuilder, queryMetrics);
    }
  }

  private void runWithCardinalityKnown(
      Parameters params,
      TopNResultBuilder resultBuilder,
      DimValSelector dimValSelector,
      @Nullable TopNQueryMetrics queryMetrics
  )
  {
    if (queryMetrics != null) {
      queryMetrics.startRecordingScanTime();
    }
    boolean hasDimValSelector = (dimValSelector != null);

    final int cardinality = params.getCardinality();
    final int numValuesPerPass = params.getNumValuesPerPass();
    int numProcessed = 0;
    long processedRows = 0;
    while (numProcessed < cardinality) {
      final int numToProcess;
      int maxNumToProcess = Math.min(numValuesPerPass, cardinality - numProcessed);


      DimValSelector theDimValSelector;
      if (!hasDimValSelector) {
        numToProcess = maxNumToProcess;
        theDimValSelector = makeDimValSelector(params, numProcessed, numToProcess);
      } else {
        //skip invalid, calculate length to have enough valid value to process or hit the end.
        numToProcess = computeNewLength(dimValSelector, numProcessed, maxNumToProcess);
        theDimValSelector = updateDimValSelector(dimValSelector, numProcessed, numToProcess);
      }

      DimValAggregateStore aggregatesStore = makeDimValAggregateStore(params);

      processedRows = scanAndAggregate(params, theDimValSelector, aggregatesStore);

      updateResults(params, theDimValSelector, aggregatesStore, resultBuilder);

      resetAggregators(aggregatesStore);

      numProcessed += numToProcess;
      if (numProcessed < cardinality) {
        params.getCursor().reset();
        params.getGranularizer().advanceToBucket(params.getGranularizer().getCurrentInterval());
      }
    }
    if (queryMetrics != null) {
      queryMetrics.addProcessedRows(processedRows);
      queryMetrics.stopRecordingScanTime();
    }
  }

  /**
   * This function currently handles TopNs on long and float columns, which do not provide cardinality or an ID lookup.
   * When cardinality is unknown, process everything in one pass.
   * Existing implementations of makeDimValSelector() require cardinality as well, so the DimValSelector is not used.
   *
   * @param params        TopN parameters from run()
   * @param resultBuilder Result builder from run()
   */
  private void runWithCardinalityUnknown(
      Parameters params,
      TopNResultBuilder resultBuilder,
      @Nullable TopNQueryMetrics queryMetrics
  )
  {
    DimValAggregateStore aggregatesStore = makeDimValAggregateStore(params);
    if (queryMetrics != null) {
      queryMetrics.startRecordingScanTime();
    }
    long processedRows = scanAndAggregate(params, null, aggregatesStore);
    updateResults(params, null, aggregatesStore, resultBuilder);
    resetAggregators(aggregatesStore);
    if (queryMetrics != null) {
      queryMetrics.addProcessedRows(processedRows);
      queryMetrics.stopRecordingScanTime();
    }
  }

  protected abstract DimValSelector makeDimValSelector(Parameters params, int numProcessed, int numToProcess);

  /**
   * Skip invalid value, calculate length to have enough valid value to process or hit the end.
   *
   * @param dimValSelector the dim value selector which record value is valid or invalid.
   * @param numProcessed   the start position to process
   * @param numToProcess   the number of valid value to process
   *
   * @return the length between which have enough valid value to process or hit the end.
   */
  protected int computeNewLength(DimValSelector dimValSelector, int numProcessed, int numToProcess)
  {
    return numToProcess;
  }

  protected abstract DimValSelector updateDimValSelector(
      DimValSelector dimValSelector,
      int numProcessed,
      int numToProcess
  );

  protected abstract DimValAggregateStore makeDimValAggregateStore(Parameters params);

  /**
   * Returns the number of processed rows (i. e. after postFilters are applied inside the cursor being processed).
   */
  protected abstract long scanAndAggregate(
      Parameters params,
      DimValSelector dimValSelector,
      DimValAggregateStore dimValAggregateStore
  );

  protected abstract void updateResults(
      Parameters params,
      DimValSelector dimValSelector,
      DimValAggregateStore dimValAggregateStore,
      TopNResultBuilder resultBuilder
  );

  protected abstract void resetAggregators(
      DimValAggregateStore dimValAggregateStore
  );

  public static class AggregatorArrayProvider extends BaseArrayProvider<Aggregator[][]>
  {
    Aggregator[][] expansionAggs;
    int cardinality;

    public AggregatorArrayProvider(
        DimensionSelector dimSelector,
        TopNQuery query,
        TopNCursorInspector cursorInspector,
        int cardinality
    )
    {
      super(dimSelector, query, cursorInspector);

      this.expansionAggs = new Aggregator[cardinality][];
      this.cardinality = cardinality;
    }

    @Override
    public Aggregator[][] build()
    {
      Pair<Integer, Integer> startEnd = computeStartEnd(cardinality);

      Arrays.fill(expansionAggs, 0, startEnd.lhs, EMPTY_ARRAY);
      Arrays.fill(expansionAggs, startEnd.lhs, startEnd.rhs, null);
      Arrays.fill(expansionAggs, startEnd.rhs, expansionAggs.length, EMPTY_ARRAY);

      return expansionAggs;
    }
  }

  protected abstract static class BaseArrayProvider<T> implements TopNMetricSpecBuilder<T>
  {
    private volatile String previousStop;
    private volatile boolean ignoreAfterThreshold;
    private volatile int ignoreFirstN;
    private volatile int keepOnlyN;

    private final IdLookup idLookup;
    private final TopNQuery query;
    private final TopNCursorInspector cursorInspector;

    public BaseArrayProvider(
        DimensionSelector dimSelector,
        TopNQuery query,
        TopNCursorInspector cursorInspector
    )
    {
      this.idLookup = dimSelector.idLookup();
      this.query = query;
      this.cursorInspector = cursorInspector;

      previousStop = null;
      ignoreAfterThreshold = false;
      ignoreFirstN = 0;
      keepOnlyN = dimSelector.getValueCardinality();

      if (keepOnlyN < 0) {
        throw new IAE("Cannot operate on a dimension with no dictionary");
      }
    }

    @Override
    public void skipTo(String previousStop)
    {
      ColumnCapabilities capabilities = cursorInspector.getColumnInspector()
                                                       .getColumnCapabilities(query.getDimensionSpec().getDimension());
      if (capabilities != null && capabilities.areDictionaryValuesSorted().isTrue()) {
        this.previousStop = previousStop;
      }
    }

    @Override
    public void ignoreAfterThreshold()
    {
      ignoreAfterThreshold = true;
    }

    @Override
    public void ignoreFirstN(int n)
    {
      ignoreFirstN = n;
    }

    @Override
    public void keepOnlyN(int n)
    {
      keepOnlyN = n;
    }

    @VisibleForTesting
    public Pair<Integer, Integer> computeStartEnd(int cardinality)
    {
      int startIndex = ignoreFirstN;

      if (previousStop != null) {
        if (idLookup == null) {
          throw new UnsupportedOperationException("Only DimensionSelectors which support idLookup() are supported yet");
        }
        int lookupId = idLookup.lookupId(previousStop) + 1;
        if (lookupId < 0) {
          lookupId *= -1;
        }
        if (lookupId > ignoreFirstN + keepOnlyN) {
          startIndex = ignoreFirstN + keepOnlyN;
        } else {
          startIndex = Math.max(lookupId, startIndex);
        }
      }

      int endIndex = Math.min(ignoreFirstN + keepOnlyN, cardinality);

      final TopNOptimizationInspector topNOptimizationInspector = cursorInspector.getOptimizationInspector();
      if (ignoreAfterThreshold &&
          query.getDimensionsFilter() == null &&
          topNOptimizationInspector != null &&
          topNOptimizationInspector.areAllDictionaryIdsPresent() &&
          query.getIntervals().stream().anyMatch(interval -> interval.contains(cursorInspector.getDataInterval()))) {
        endIndex = Math.min(endIndex, startIndex + query.getThreshold());
      }

      return Pair.of(startIndex, endIndex);
    }
  }

  public static TopNResultBuilder makeResultBuilder(TopNParams params, TopNQuery query)
  {
    final Comparator comparator = query.getTopNMetricSpec()
                                       .getComparator(query.getAggregatorSpecs(), query.getPostAggregatorSpecs());
    return query.getTopNMetricSpec().getResultBuilder(
        params.getGranularizer().getBucketStart(),
        query.getDimensionSpec(),
        query.getThreshold(),
        comparator,
        query.getAggregatorSpecs(),
        query.getPostAggregatorSpecs()
    );
  }
}
