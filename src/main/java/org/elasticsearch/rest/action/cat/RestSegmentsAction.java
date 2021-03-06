/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.rest.action.cat;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.action.admin.indices.segments.*;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.Table;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.engine.Segment;
import org.elasticsearch.rest.*;
import org.elasticsearch.rest.action.support.RestTable;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.rest.RestRequest.Method.GET;

public class RestSegmentsAction extends AbstractCatAction {

    @Inject
    public RestSegmentsAction(Settings settings, Client client, RestController controller) {
        super(settings, client);
        controller.registerHandler(GET, "/_cat/segments", this);
        controller.registerHandler(GET, "/_cat/segments/{index}", this);
    }

    @Override
    void doRequest(final RestRequest request, final RestChannel channel) {
        final String[] indices = Strings.splitStringByCommaToArray(request.param("index"));

        final ClusterStateRequest clusterStateRequest = new ClusterStateRequest();
        clusterStateRequest.local(request.paramAsBoolean("local", clusterStateRequest.local()));
        clusterStateRequest.masterNodeTimeout(request.paramAsTime("master_timeout", clusterStateRequest.masterNodeTimeout()));
        clusterStateRequest.clear().nodes(true).routingTable(true).indices(indices);

        client.admin().cluster().state(clusterStateRequest, new AbstractRestResponseActionListener<ClusterStateResponse>(request, channel, logger) {
            @Override
            public void onResponse(final ClusterStateResponse clusterStateResponse) {
                final IndicesSegmentsRequest indicesSegmentsRequest = new IndicesSegmentsRequest();
                indicesSegmentsRequest.indices(indices);

                client.admin().indices().segments(indicesSegmentsRequest, new ActionListener<IndicesSegmentResponse>() {
                    @Override
                    public void onResponse(final IndicesSegmentResponse indicesSegmentResponse) {
                        final Map<String, IndexSegments> indicesSegments = indicesSegmentResponse.getIndices();
                        try {
                            Table tab = buildTable(request, clusterStateResponse, indicesSegments);
                            channel.sendResponse(RestTable.buildResponse(tab, request, channel));
                        } catch (Throwable e) {
                            onFailure(e);
                        }
                    }

                    @Override
                    public void onFailure(Throwable e) {
                        try {
                            channel.sendResponse(new XContentThrowableRestResponse(request, e));
                        } catch (IOException e1) {
                            logger.error("Failed to send failure response", e1);
                        }
                    }
                });

            }

            @Override
            public void onFailure(Throwable e) {
                try {
                    channel.sendResponse(new XContentThrowableRestResponse(request, e));
                } catch (IOException e1) {
                    logger.error("Failed to send failure response", e1);
                }
            }
        });
    }

    @Override
    void documentation(StringBuilder sb) {
        sb.append("/_cat/segments\n");
        sb.append("/_cat/segments/{index}\n");
    }

    @Override
    Table getTableWithHeader(RestRequest request) {
        Table table = new Table();
        table.startHeaders();
        table.addCell("index", "default:true;alias:i,idx;desc:index name");
        table.addCell("shard", "default:true;alias:s,sh;desc:shard name");
        table.addCell("prirep", "alias:p,pr,primaryOrReplica;default:true;desc:primary or replica");
        table.addCell("ip", "default:true;desc:ip of node where it lives");
        table.addCell("segment", "default:true;alias:seg;desc:segment name");
        table.addCell("generation", "default:true;alias:g,gen;text-align:right;desc:segment generation");
        table.addCell("docs.count", "default:true;alias:dc,docsCount;text-align:right;desc:number of docs in segment");
        table.addCell("docs.deleted", "default:true;alias:dd,docsDeleted;text-align:right;desc:number of deleted docs in segment");
        table.addCell("size", "default:true;alias:si;text-align:right;desc:segment size in bytes");
        table.addCell("size.memory", "default:true;alias:sm,sizeMemory;text-align:right;desc:segment memory in bytes");
        table.addCell("committed", "default:true;alias:ic,isCommitted;desc:is segment committed");
        table.addCell("searchable", "default:true;alias:is,isSearchable;desc:is segment searched");
        table.addCell("version", "default:true;alias:v,ver;desc:version");
        table.addCell("compound", "default:true;alias:ico,isCompound;desc:is segment compound");
        table.endHeaders();
        return table;
    }

    private Table buildTable(final RestRequest request, ClusterStateResponse state, Map<String, IndexSegments> indicesSegments) {
        Table table = getTableWithHeader(request);

        DiscoveryNodes nodes = state.getState().nodes();

        for (IndexSegments indexSegments : indicesSegments.values()) {
            Map<Integer, IndexShardSegments> shards = indexSegments.getShards();

            for (IndexShardSegments indexShardSegments : shards.values()) {
                ShardSegments[] shardSegments = indexShardSegments.getShards();

                for (ShardSegments shardSegment : shardSegments) {
                    List<Segment> segments = shardSegment.getSegments();

                    for (Segment segment : segments) {
                        table.startRow();

                        table.addCell(shardSegment.getIndex());
                        table.addCell(shardSegment.getShardId());
                        table.addCell(shardSegment.getShardRouting().primary() ? "p" : "r");
                        table.addCell(nodes.get(shardSegment.getShardRouting().currentNodeId()).getHostAddress());
                        table.addCell(segment.getName());
                        table.addCell(segment.getGeneration());
                        table.addCell(segment.getNumDocs());
                        table.addCell(segment.getDeletedDocs());
                        table.addCell(segment.getSize());
                        table.addCell(segment.getMemoryInBytes());
                        table.addCell(segment.isCommitted());
                        table.addCell(segment.isSearch());
                        table.addCell(segment.getVersion());
                        table.addCell(segment.isCompound());

                        table.endRow();
                    }

                }
            }

        }

        return table;
    }
}
