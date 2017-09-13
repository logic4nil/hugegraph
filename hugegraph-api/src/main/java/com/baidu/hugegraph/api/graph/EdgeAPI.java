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

package com.baidu.hugegraph.api.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;

import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.util.function.TriFunction;
import org.slf4j.Logger;

import com.baidu.hugegraph.HugeException;
import com.baidu.hugegraph.HugeGraph;
import com.baidu.hugegraph.api.API;
import com.baidu.hugegraph.api.filter.CompressInterceptor.Compress;
import com.baidu.hugegraph.api.filter.DecompressInterceptor.Decompress;
import com.baidu.hugegraph.api.filter.StatusFilter.Status;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.config.ServerOptions;
import com.baidu.hugegraph.core.GraphManager;
import com.baidu.hugegraph.exception.NotFoundException;
import com.baidu.hugegraph.schema.VertexLabel;
import com.baidu.hugegraph.server.HugeServer;
import com.baidu.hugegraph.structure.HugeElement;
import com.baidu.hugegraph.structure.HugeVertex;
import com.baidu.hugegraph.util.E;
import com.baidu.hugegraph.util.Log;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@Path("graphs/{graph}/graph/edges")
@Singleton
public class EdgeAPI extends API {

    private static final Logger LOG = Log.logger(HugeServer.class);

    @POST
    @Status(Status.CREATED)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    public String create(@Context GraphManager manager,
                         @PathParam("graph") String graph,
                         CreateEdge edge) {
        LOG.debug("Graph [{}] create edge: {}", graph, edge);

        E.checkArgumentNotNull(edge.source, "Expect source vertex id");
        E.checkArgumentNotNull(edge.target, "Expect target vertex id");

        Graph g = graph(manager, graph);
        /*
         * NOTE: If the vertex id is correct but label is null or incorrect,
         * we allow to create it here
         */
        Vertex srcVertex = getVertex((HugeGraph) g, edge.source, null);
        Vertex tgtVertex = getVertex((HugeGraph) g, edge.target, null);
        Edge e = srcVertex.addEdge(edge.label, tgtVertex, edge.properties());

        return manager.serializer(g).writeEdge(e);
    }

    @POST
    @Decompress
    @Path("batch")
    @Status(Status.CREATED)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    public List<String> create(
            @Context GraphManager manager,
            @PathParam("graph") String graph,
            @QueryParam("checkVertex") @DefaultValue("true") boolean checkV,
            List<CreateEdge> edges) {
        HugeGraph g = (HugeGraph) graph(manager, graph);

        TriFunction<HugeGraph, String, String, Vertex> getVertex =
                    checkV ? EdgeAPI::getVertex : EdgeAPI::newVertex;

        final int maxEdges = g.configuration()
                              .get(ServerOptions.MAX_EDGES_PER_BATCH);
        if (edges.size() > maxEdges) {
            throw new HugeException(
                      "Too many counts of edges for one time post, " +
                      "the maximum number is '%s'", maxEdges);
        }

        LOG.debug("Graph [{}] create edges: {}", graph, edges);

        List<String> ids = new ArrayList<>(edges.size());

        g.tx().open();
        try {
            for (CreateEdge edge : edges) {
                E.checkArgumentNotNull(edge.source,
                                       "Expect source vertex id");
                E.checkArgumentNotNull(edge.sourceLabel,
                                       "Expect source vertex label");
                E.checkArgumentNotNull(edge.target,
                                       "Expect target vertex id");
                E.checkArgumentNotNull(edge.targetLabel,
                                       "Expect target vertex label");

                Vertex srcVertex = getVertex.apply(g, edge.source,
                                                   edge.sourceLabel);
                Vertex tgtVertex = getVertex.apply(g, edge.target,
                                                   edge.targetLabel);
                Edge result = srcVertex.addEdge(edge.label, tgtVertex,
                                                edge.properties());
                ids.add(result.id().toString());
            }
            g.tx().commit();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e1) {
            LOG.error("Failed to add edges", e1);
            try {
                g.tx().rollback();
            } catch (Exception e2) {
                LOG.error("Failed to rollback edges", e2);
            }
            throw new HugeException("Failed to add edges", e1);
        } finally {
            g.tx().close();
        }
        return ids;
    }

    @GET
    @Compress
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    public String list(@Context GraphManager manager,
                       @PathParam("graph") String graph,
                       @QueryParam("limit") @DefaultValue("100") long limit) {
        LOG.debug("Graph [{}] get vertices", graph);

        Graph g = graph(manager, graph);
        List<Edge> rs = g.traversal().E().limit(limit).toList();
        return manager.serializer(g).writeEdges(rs);
    }

    @GET
    @Path("{id}")
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    public String get(@Context GraphManager manager,
                      @PathParam("graph") String graph,
                      @PathParam("id") String id) {
        LOG.debug("Graph [{}] get vertex by id '{}'", graph, id);

        Graph g = graph(manager, graph);
        return manager.serializer(g).writeEdge(g.edges(id).next());
    }

    @DELETE
    @Path("{id}")
    @Consumes(APPLICATION_JSON)
    public void delete(@Context GraphManager manager,
                       @PathParam("graph") String graph,
                       @PathParam("id") String id) {
        LOG.debug("Graph [{}] remove vertex by id '{}'", graph, id);

        Graph g = graph(manager, graph);
        // TODO: add removeEdge(id) to improve
        g.edges(id).next().remove();
    }

    private static Vertex getVertex(HugeGraph graph, String id, String label) {
        try {
            return graph.traversal().V(id).next();
        } catch (NotFoundException e) {
            throw new IllegalArgumentException(
                      String.format("Invalid vertex id '%s'", id));
        }
    }

    private static Vertex newVertex(HugeGraph graph, String id, String label) {
        VertexLabel vl = graph.schemaTransaction().getVertexLabel(label);
        E.checkArgumentNotNull(vl, "Invalid vertex label '%s'", label);
        Id idValue = HugeElement.getIdValue(id);
        Vertex vertex = new HugeVertex(graph, idValue, vl);
        return vertex;
    }

    @JsonIgnoreProperties(value = {"type"})
    static class CreateEdge {

        @JsonProperty("outV")
        public String source;
        @JsonProperty("outVLabel")
        public String sourceLabel;
        public String label;
        @JsonProperty("inV")
        public String target;
        @JsonProperty("inVLabel")
        public String targetLabel;
        public Map<String, Object> properties;
        public String type;

        public Object[] properties() {
            return API.properties(this.properties);
        }

        @Override
        public String toString() {
            return String.format("CreateEdge{label=%s, " +
                                 "source-vertex=%s, source-vertex-label=%s, " +
                                 "target-vertex=%s, target-vertex-label=%s, " +
                                 "properties=%s}",
                                 this.label, this.source, this.sourceLabel,
                                 this.target, this.targetLabel,
                                 this.properties);
        }
    }
}
