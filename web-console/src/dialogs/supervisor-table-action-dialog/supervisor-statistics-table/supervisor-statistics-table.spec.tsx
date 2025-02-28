/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { QueryState } from '../../../utils';
import { shallow } from '../../../utils/shallow-renderer';

import type { SupervisorStatisticsTableRow } from './supervisor-statistics-table';
import {
  normalizeSupervisorStatisticsResults,
  SupervisorStatisticsTable,
} from './supervisor-statistics-table';

let supervisorStatisticsState: QueryState<SupervisorStatisticsTableRow[]> = QueryState.INIT;
jest.mock('../../../hooks/use-query-manager', () => {
  return {
    useQueryManager: () => [supervisorStatisticsState],
  };
});

describe('SupervisorStatisticsTable', () => {
  function makeSupervisorStatisticsTable() {
    return <SupervisorStatisticsTable supervisorId="sup-id" downloadFilename="test" />;
  }

  it('matches snapshot on init', () => {
    expect(shallow(makeSupervisorStatisticsTable())).toMatchSnapshot();
  });

  it('matches snapshot on loading', () => {
    supervisorStatisticsState = QueryState.LOADING;

    expect(shallow(makeSupervisorStatisticsTable())).toMatchSnapshot();
  });

  it('matches snapshot on error', () => {
    supervisorStatisticsState = new QueryState({ error: new Error('test error') });

    expect(shallow(makeSupervisorStatisticsTable())).toMatchSnapshot();
  });

  it('matches snapshot on no data', () => {
    supervisorStatisticsState = new QueryState({
      data: normalizeSupervisorStatisticsResults({}),
    });

    expect(shallow(makeSupervisorStatisticsTable())).toMatchSnapshot();
  });

  it('matches snapshot on some data', () => {
    supervisorStatisticsState = new QueryState({
      data: normalizeSupervisorStatisticsResults({
        '0': {
          index_kafka_github_dfde87f265a8cc9_pnmcaldn: {
            movingAverages: {
              buildSegments: {
                '5m': {
                  processed: 3.5455993615040584,
                  processedBytes: 10,
                  unparseable: 0,
                  thrownAway: 0,
                  processedWithError: 0,
                },
                '15m': {
                  processed: 5.544749689510444,
                  processedBytes: 20,
                  unparseable: 0,
                  thrownAway: 0,
                  processedWithError: 0,
                },
                '1m': {
                  processed: 4.593670088770785,
                  processedBytes: 30,
                  unparseable: 0,
                  thrownAway: 0,
                  processedWithError: 0,
                },
              },
            },
            totals: {
              buildSegments: {
                processed: 7516,
                processedBytes: 60,
                processedWithError: 0,
                thrownAway: 0,
                unparseable: 0,
              },
            },
          },
        },
      }),
    });

    expect(shallow(makeSupervisorStatisticsTable())).toMatchSnapshot();
  });
});
