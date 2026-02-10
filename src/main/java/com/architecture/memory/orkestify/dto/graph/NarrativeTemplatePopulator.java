package com.architecture.memory.orkestify.dto.graph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NarrativeTemplatePopulator {
    public static String populate(String template, GraphVisualizationResponse graph) {
        if (graph == null || graph.getNodes() == null || graph.getEdges() == null) {
            return template;
        }
        Map<String, String> values = new HashMap<>();
        // Find ENTRY_ENDPOINT (Endpoint node not target of any RESOLVES_TO edge)
        GraphNode entryEndpoint = null;
        for (GraphNode node : graph.getNodes()) {
            if ("Endpoint".equals(node.getType())) {
                boolean isTarget = false;
                for (GraphEdge edge : graph.getEdges()) {
                    if ("RESOLVES_TO".equals(edge.getType()) && node.getId().equals(edge.getTarget())) {
                        isTarget = true;
                        break;
                    }
                }
                if (!isTarget) {
                    entryEndpoint = node;
                    break;
                }
            }
        }
        if (entryEndpoint != null) {
            values.put("ENTRY_ENDPOINT", getProperty(entryEndpoint, "path"));
            values.put("ENTRY_HANDLER_METHOD", getProperty(entryEndpoint, "handlerMethod"));
        }
        // ENTRY_CONTROLLER (Controller connected to ENTRY_ENDPOINT via HAS_ENDPOINT)
        if (entryEndpoint != null) {
            for (GraphEdge edge : graph.getEdges()) {
                if ("HAS_ENDPOINT".equals(edge.getType()) && edge.getTarget().equals(entryEndpoint.getId())) {
                    GraphNode controller = findNodeById(graph.getNodes(), edge.getSource(), "Controller");
                    if (controller != null) {
                        values.put("ENTRY_CONTROLLER", getProperty(controller, "className"));
                    }
                }
            }
        }
        // INTERNAL_METHOD_NAME & INTERNAL_METHOD_CLASS (Method node connected via CALLS from ENTRY_ENDPOINT)
        if (entryEndpoint != null) {
            for (GraphEdge edge : graph.getEdges()) {
                if ("CALLS".equals(edge.getType()) && edge.getSource().equals(entryEndpoint.getId())) {
                    GraphNode method = findNodeById(graph.getNodes(), edge.getTarget(), "Method");
                    if (method != null) {
                        values.put("INTERNAL_METHOD_NAME", getProperty(method, "methodName"));
                        values.put("INTERNAL_METHOD_CLASS", getProperty(method, "className"));
                    }
                }
            }
        }
        // EXTERNAL_HTTP_METHOD, EXTERNAL_ENDPOINT, EXTERNAL_CLIENT_TYPE (ExternalCall node via MAKES_EXTERNAL_CALL)
        if (entryEndpoint != null) {
            for (GraphEdge edge : graph.getEdges()) {
                if ("MAKES_EXTERNAL_CALL".equals(edge.getType()) && edge.getSource().equals(entryEndpoint.getId())) {
                    GraphNode externalCall = findNodeById(graph.getNodes(), edge.getTarget(), "ExternalCall");
                    if (externalCall != null) {
                        values.put("EXTERNAL_HTTP_METHOD", getProperty(externalCall, "httpMethod"));
                        values.put("EXTERNAL_ENDPOINT", getProperty(externalCall, "url"));
                        values.put("EXTERNAL_CLIENT_TYPE", getProperty(externalCall, "clientType"));
                        // RESOLVED_ENDPOINT (Endpoint node via RESOLVES_TO from ExternalCall)
                        for (GraphEdge edge2 : graph.getEdges()) {
                            if ("RESOLVES_TO".equals(edge2.getType()) && edge2.getSource().equals(externalCall.getId())) {
                                GraphNode resolvedEndpoint = findNodeById(graph.getNodes(), edge2.getTarget(), "Endpoint");
                                if (resolvedEndpoint != null) {
                                    values.put("RESOLVED_ENDPOINT", getProperty(resolvedEndpoint, "path"));
                                    // RESOLVED_CONTROLLER (Controller via BELONGS_TO from RESOLVED_ENDPOINT)
                                    for (GraphEdge edge3 : graph.getEdges()) {
                                        if ("BELONGS_TO".equals(edge3.getType()) && edge3.getSource().equals(resolvedEndpoint.getId())) {
                                            GraphNode resolvedController = findNodeById(graph.getNodes(), edge3.getTarget(), "Controller");
                                            if (resolvedController != null) {
                                                values.put("RESOLVED_CONTROLLER", getProperty(resolvedController, "className"));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Replace placeholders
        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }
    private static GraphNode findNodeById(List<GraphNode> nodes, String id, String type) {
        for (GraphNode node : nodes) {
            if (node.getId().equals(id) && node.getType().equals(type)) {
                return node;
            }
        }
        return null;
    }
    private static String getProperty(GraphNode node, String key) {
        if (node.getProperties() != null && node.getProperties().containsKey(key)) {
            Object val = node.getProperties().get(key);
            return val != null ? val.toString() : null;
        }
        return null;
    }
}

