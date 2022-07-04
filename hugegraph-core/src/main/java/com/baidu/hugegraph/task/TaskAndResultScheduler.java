/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.task;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import com.baidu.hugegraph.backend.query.Condition;
import com.baidu.hugegraph.backend.query.ConditionQuery;
import com.baidu.hugegraph.exception.NotFoundException;
import com.baidu.hugegraph.iterator.ListIterator;
import com.baidu.hugegraph.schema.PropertyKey;
import com.baidu.hugegraph.schema.VertexLabel;
import com.baidu.hugegraph.type.HugeType;
import com.baidu.hugegraph.type.define.HugeKeys;
import com.google.common.collect.ImmutableMap;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import com.baidu.hugegraph.HugeGraphParams;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.backend.query.QueryResults;
import com.baidu.hugegraph.backend.store.BackendStore;
import com.baidu.hugegraph.iterator.MapperIterator;
import com.baidu.hugegraph.structure.HugeVertex;
import com.baidu.hugegraph.util.E;

/**
 * Base class of task&result scheduler
 */
public abstract class TaskAndResultScheduler extends TaskScheduler {

    public TaskAndResultScheduler(
            HugeGraphParams graph,
            ExecutorService serverInfoDbExecutor) {
        super(graph, serverInfoDbExecutor);
    }

    public TaskAndResultScheduler(TaskAndResultScheduler another) {
        super(another);
    }

    /**
     * Task transactions, for persistence
     */
    protected volatile TaskAndResultTransaction taskTx = null;

    public <V> void save(HugeTask<V> task) {
        E.checkArgumentNotNull(task, "Task can't be null");
        String rawResult = task.result();

        // Save task without result;
        this.call(() -> {
            // Construct vertex from task
            HugeVertex vertex = this.tx().constructTaskVertex(task);
            // Delete index of old vertex to avoid stale index
            this.tx().deleteIndex(vertex);
            // Add or update task info to backend store
            return this.tx().addVertex(vertex);
        });

        // 保存result 结果
        if (rawResult != null) {
            HugeTaskResult result = new HugeTaskResult(task.id().asLong());
            result.result(rawResult);

            this.call(() -> {
                // Construct vertex from task
                HugeVertex vertex = this.tx().constructTaskResultVertex(result);
                // Delete index of old vertex to avoid stale index
                this.tx().deleteIndex(vertex);
                // Add or update task info to backend store
                return this.tx().addVertex(vertex);
            });
        }
    }


    @Override
    public <V> HugeTask<V> task(Id id) {
        HugeTask<V> task =  this.call(() -> {
            Iterator<Vertex> vertices = this.tx().queryVertices(id);
            Vertex vertex = QueryResults.one(vertices);
            if (vertex == null) {
                return null;
            }
            return HugeTask.fromVertex(vertex);
        });

        if (task == null) {
            throw new NotFoundException("Can't find task with id '%s'", id);
        }

        HugeTaskResult taskResult = queryTaskResult(id);
        if (taskResult != null) {
            task.result(taskResult);
        }

        return task;
    }

    @Override
    public <V> Iterator<HugeTask<V>> tasks(List<Id> ids) {
        return this.queryTask(ids);
    }

    @Override
    public <V> Iterator<HugeTask<V>> tasks(TaskStatus status, long limit,
                                           String page) {
        if (status == null) {
            return this.queryTask(ImmutableMap.of(), limit, page);
        }
        return this.queryTask(HugeTask.P.STATUS, status.code(), limit, page);
    }

    protected <V> Iterator<HugeTask<V>> queryTask(String key, Object value,
                                                  long limit, String page) {
        return this.queryTask(ImmutableMap.of(key, value), limit, page);
    }

    protected <V> Iterator<HugeTask<V>> queryTask(Map<String, Object> conditions,
                                                  long limit, String page) {
        Iterator<HugeTask<V>> ts = this.call(() -> {
            ConditionQuery query = new ConditionQuery(HugeType.VERTEX);
            if (page != null) {
                query.page(page);
            }
            VertexLabel vl = this.graph().vertexLabel(HugeTask.P.TASK);
            query.eq(HugeKeys.LABEL, vl.id());
            for (Map.Entry<String, Object> entry : conditions.entrySet()) {
                PropertyKey pk = this.graph().propertyKey(entry.getKey());
                query.query(Condition.eq(pk.id(), entry.getValue()));
            }
            query.showHidden(true);
            if (limit != NO_LIMIT) {
                query.limit(limit);
            }
            Iterator<Vertex> vertices = this.tx().queryVertices(query);
            Iterator<HugeTask<V>> tasks =
                    new MapperIterator<>(vertices, HugeTask::fromVertex);
            // Convert iterator to list to avoid across thread tx accessed
            return QueryResults.toList(tasks);
        });

        return new MapperIterator<>(ts, (task) -> {
            HugeTaskResult taskResult = queryTaskResult(task.id());
            if (taskResult != null) {
                task.result(taskResult);
            }
            return task;
        });
    }

    protected <V> Iterator<HugeTask<V>> queryTask(List<Id> ids) {
        ListIterator<HugeTask<V>> ts = this.call(
                () -> {
                    Object[] idArray = ids.toArray(new Id[ids.size()]);
                    Iterator<Vertex> vertices = this.tx()
                                                    .queryVertices(idArray);
                    Iterator<HugeTask<V>> tasks =
                            new MapperIterator<>(vertices,
                                                 HugeTask::fromVertex);
                    // Convert iterator to list to avoid across thread tx accessed
                    return QueryResults.toList(tasks);
                });

        Iterator<HugeTaskResult> results = queryTaskResult(ids);

        HashMap<Long, HugeTaskResult> resultCaches = new HashMap<>();
        while (results.hasNext()) {
            HugeTaskResult entry = results.next();
            resultCaches.put(entry.taskId(), entry);
        }

        return new MapperIterator<>(ts, (task) -> {
            HugeTaskResult taskResult = resultCaches.get(task.id());
            if (taskResult != null) {
                task.result(taskResult);
            }
            return task;
        });
    }

    public <V> HugeTask<V> taskWithoutResult(Id id) {

        HugeTask<V> result = this.call(() -> {
            Iterator<Vertex> vertices = this.tx().queryVertices(id);
            Vertex vertex = QueryResults.one(vertices);
            if (vertex == null) {
                return null;
            }
            return HugeTask.fromVertex(vertex);
        });

        return result;
    }

    public <V> Iterator<HugeTask<V>> tasksWithoutResult(List<Id> ids) {
        return this.call(() -> {
            Object[] idArray = ids.toArray(new Id[ids.size()]);
            Iterator<Vertex> vertices = this.tx().queryVertices(idArray);
            Iterator<HugeTask<V>> tasks =
                    new MapperIterator<>(vertices, HugeTask::fromVertex);
            // Convert iterator to list to avoid across thread tx accessed
            return QueryResults.toList(tasks);
        });
    }

    public <V> Iterator<HugeTask<V>> tasksWithoutResult(TaskStatus status,
                                                        long limit,
                                                        String page) {

        if (status == null) {
            return this.queryTaskWithoutResult(ImmutableMap.of(), limit, page);
        }
        return this.queryTaskWithoutResult(HugeTask.P.STATUS, status.code(),
                                           limit, page);
    }

    protected <V> Iterator<HugeTask<V>> queryTaskWithoutResult(String key,
                                                               Object value,
                                                               long limit, String page) {
        return this.queryTaskWithoutResult(ImmutableMap.of(key, value), limit, page);
    }

    protected <V> Iterator<HugeTask<V>> queryTaskWithoutResult(Map<String,
            Object> conditions, long limit, String page) {
        return this.call(() -> {
            ConditionQuery query = new ConditionQuery(HugeType.VERTEX);
            if (page != null) {
                query.page(page);
            }
            VertexLabel vl = this.graph().vertexLabel(HugeTask.P.TASK);
            query.eq(HugeKeys.LABEL, vl.id());
            for (Map.Entry<String, Object> entry : conditions.entrySet()) {
                PropertyKey pk = this.graph().propertyKey(entry.getKey());
                query.query(Condition.eq(pk.id(), entry.getValue()));
            }
            query.showHidden(true);
            if (limit != NO_LIMIT) {
                query.limit(limit);
            }
            Iterator<Vertex> vertices = this.tx().queryVertices(query);
            Iterator<HugeTask<V>> tasks =
                    new MapperIterator<>(vertices, HugeTask::fromVertex);
            // Convert iterator to list to avoid across thread tx accessed
            return QueryResults.toList(tasks);
        });
    }


    protected HugeTaskResult queryTaskResult(Id taskid) {

        HugeTaskResult result =  this.call(() -> {
            ConditionQuery query = new ConditionQuery(HugeType.VERTEX);
            VertexLabel vl = this.graph().vertexLabel(HugeTaskResult.P.TASKRESULT);
            query.eq(HugeKeys.LABEL, vl.id());
            PropertyKey pk = this.graph().propertyKey(HugeTaskResult.P.TASKID);
            query.query(Condition.eq(pk.id(), taskid.asLong()));
            query.showHidden(true);
            Iterator<Vertex> vertices = this.tx().queryVertices(query);
            Vertex vertex = QueryResults.one(vertices);
            if (vertex == null) {
                return null;
            }
            return HugeTaskResult.fromVertex(vertex);
        });

        return result;
    }

    protected Iterator<HugeTaskResult> queryTaskResult(List<Id> taskids) {
        return this.call(() -> {

            ConditionQuery query = new ConditionQuery(HugeType.VERTEX);
            VertexLabel vl = this.graph().vertexLabel(HugeTaskResult.P.TASKRESULT);
            query.eq(HugeKeys.LABEL, vl.id());
            PropertyKey pk = this.graph().propertyKey(HugeTaskResult.P.TASKID);
            query.query(Condition.in(pk.id(), taskids));
            query.showHidden(true);

            Iterator<Vertex> vertices = this.tx().queryVertices(query);
            Iterator<HugeTaskResult> tasks =
                    new MapperIterator<>(vertices, HugeTaskResult::fromVertex);
            // Convert iterator to list to avoid across thread tx accessed
            return QueryResults.toList(tasks);
        });
    }

    protected TaskAndResultTransaction tx() {
        // NOTE: only the owner thread can access task tx
        if (this.taskTx == null) {
            /*
             * NOTE: don't synchronized(this) due to scheduler thread hold
             * this lock through scheduleTasks(), then query tasks and wait
             * for db-worker thread after call(), the tx may not be initialized
             * but can't catch this lock, then cause dead lock.
             * We just use this.eventListener as a monitor here
             */
            synchronized (this.eventListener) {
                if (this.taskTx == null) {
                    BackendStore store = this.graph.loadSystemStore();
                    TaskAndResultTransaction tx =
                            new TaskAndResultTransaction(this.graph, store);
                    assert this.taskTx == null; // may be reentrant?
                    this.taskTx = tx;
                }
            }
        }
        assert this.taskTx != null;
        return this.taskTx;
    }
}
