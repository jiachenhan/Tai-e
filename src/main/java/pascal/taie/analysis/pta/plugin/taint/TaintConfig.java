/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.pta.plugin.taint;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.analysis.pta.plugin.util.InvokeUtils;
import pascal.taie.config.ConfigException;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;
import pascal.taie.language.type.TypeSystem;
import pascal.taie.util.collection.Lists;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Configuration for taint analysis.
 */
record TaintConfig(List<CallSource> callSources,
                   List<ParamSource> paramSources,
                   List<Sink> sinks,
                   List<TaintTransfer> transfers,
                   List<ParamSanitizer> paramSanitizers) {

    private static final Logger logger = LogManager.getLogger(TaintConfig.class);

    /**
     * An empty taint config.
     */
    private static final TaintConfig EMPTY = new TaintConfig(
            List.of(), List.of(), List.of(), List.of(), List.of());

    /**
     * Loads a taint analysis configuration from given path.
     * If the path is a file, then loads config from the file;
     * if the path is a directory, then loads all YAML files in the directory
     * and merge them as the result.
     *
     * @param path       the path
     * @param hierarchy  the class hierarchy
     * @param typeSystem the type manager
     * @return the resulting {@link TaintConfig}
     * @throws ConfigException if failed to load the config
     */
    static TaintConfig loadConfig(
            String path, ClassHierarchy hierarchy, TypeSystem typeSystem) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        SimpleModule module = new SimpleModule();
        module.addDeserializer(TaintConfig.class,
                new Deserializer(hierarchy, typeSystem));
        mapper.registerModule(module);
        File file = new File(path);
        logger.info("Loading taint config from {}", file.getAbsolutePath());
        if (file.isFile()) {
            return loadSingle(mapper, file);
        } else if (file.isDirectory()) {
            // if file is a directory, then load all YAML files
            // in the directory and merge them as the result
            TaintConfig[] result = new TaintConfig[]{ EMPTY };
            try (Stream<Path> paths = Files.walk(file.toPath())) {
                paths.filter(TaintConfig::isYAML)
                        .map(p -> loadSingle(mapper, p.toFile()))
                        .forEach(tc -> result[0] = result[0].mergeWith(tc));
                return result[0];
            } catch (IOException e) {
                throw new ConfigException("Failed to load taint config from " + file, e);
            }
        } else {
            throw new ConfigException(path + " is neither a file nor a directory");
        }
    }

    /**
     * Loads taint config from a single file.
     */
    private static TaintConfig loadSingle(ObjectMapper mapper, File file) {
        try {
            return mapper.readValue(file, TaintConfig.class);
        } catch (IOException e) {
            throw new ConfigException("Failed to load taint config from " + file, e);
        }
    }

    private static boolean isYAML(Path path) {
        String pathStr = path.toString();
        return pathStr.endsWith(".yml") || pathStr.endsWith(".yaml");
    }

    /**
     * Merges this taint config with other taint config.
     * @return a new merged taint config.
     */
    TaintConfig mergeWith(TaintConfig other) {
        return new TaintConfig(
                Lists.concatDistinct(callSources, other.callSources),
                Lists.concatDistinct(paramSources, other.paramSources),
                Lists.concatDistinct(sinks, other.sinks),
                Lists.concatDistinct(transfers, other.transfers),
                Lists.concatDistinct(paramSanitizers, other.paramSanitizers));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TaintConfig:");
        if (!callSources.isEmpty() || !paramSources.isEmpty()) {
            sb.append("\nsources:\n");
            callSources.forEach(source ->
                    sb.append("  ").append(source).append("\n"));
            paramSources.forEach(source ->
                    sb.append("  ").append(source).append("\n"));
        }
        if (!sinks.isEmpty()) {
            sb.append("\nsinks:\n");
            sinks.forEach(sink ->
                    sb.append("  ").append(sink).append("\n"));
        }
        if (!transfers.isEmpty()) {
            sb.append("\ntransfers:\n");
            transfers.forEach(transfer ->
                    sb.append("  ").append(transfer).append("\n"));
        }
        if (!paramSanitizers.isEmpty()) {
            sb.append("\nsanitizers:\n");
            paramSanitizers.forEach(sanitizer ->
                    sb.append("  ").append(sanitizer).append("\n"));
        }
        return sb.toString();
    }

    /**
     * Deserializer for {@link TaintConfig}.
     */
    private static class Deserializer extends JsonDeserializer<TaintConfig> {

        private final ClassHierarchy hierarchy;

        private final TypeSystem typeSystem;

        private Deserializer(ClassHierarchy hierarchy, TypeSystem typeSystem) {
            this.hierarchy = hierarchy;
            this.typeSystem = typeSystem;
        }

        @Override
        public TaintConfig deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            ObjectCodec oc = p.getCodec();
            JsonNode node = oc.readTree(p);
            List<Source> sources = deserializeSources(node.get("sources"));
            List<CallSource> callSources = sources.stream()
                    .filter(s -> s instanceof CallSource)
                    .map(s -> (CallSource) s)
                    .toList();
            List<ParamSource> paramSources = sources.stream()
                    .filter(s -> s instanceof ParamSource)
                    .map(s -> (ParamSource) s)
                    .toList();
            List<Sink> sinks = deserializeSinks(node.get("sinks"));
            List<TaintTransfer> transfers = deserializeTransfers(node.get("transfers"));
            List<ParamSanitizer> sanitizers = deserializeSanitizers(node.get("sanitizers"));
            return new TaintConfig(callSources, paramSources, sinks, transfers, sanitizers);
        }

        /**
         * Deserializes a {@link JsonNode} (assume it is an {@link ArrayNode})
         * to a list of {@link Source}.
         *
         * @param node the node to be deserialized
         * @return list of deserialized {@link Source}
         */
        private List<Source> deserializeSources(JsonNode node) {
            if (node instanceof ArrayNode arrayNode) {
                List<Source> sources = new ArrayList<>(arrayNode.size());
                for (JsonNode elem : arrayNode) {
                    JsonNode sourceKind = elem.get("kind");
                    Source source;
                    if (sourceKind != null) {
                        source = switch (sourceKind.asText()) {
                            case "call" -> deserializeCallSource(elem);
                            case "param" -> deserializeParamSource(elem);
                            default -> {
                                logger.warn("Unknown source kind \"{}\" in {}",
                                        sourceKind.asText(), elem.toString());
                                yield null;
                            }
                        };
                    } else {
                        logger.warn("Ignore {} due to missing source \"kind\"",
                                elem.toString());
                        source = null;
                    }
                    if (source != null) {
                        sources.add(source);
                    }
                }
                return Collections.unmodifiableList(sources);
            } else {
                // if node is not an instance of ArrayNode, just return an empty set.
                return List.of();
            }
        }

        @Nullable
        private CallSource deserializeCallSource(JsonNode node) {
            String methodSig = node.get("method").asText();
            JMethod method = hierarchy.getMethod(methodSig);
            if (method != null) {
                int index = InvokeUtils.toInt(node.get("index").asText());
                Type type = typeSystem.getType(node.get("type").asText());
                return new CallSource(method, index, type);
            } else {
                // if the method (given in config file) is absent in
                // the class hierarchy, just ignore it.
                logger.warn("Cannot find source method '{}'", methodSig);
                return null;
            }
        }

        @Nullable
        private ParamSource deserializeParamSource(JsonNode node) {
            String methodSig = node.get("method").asText();
            JMethod method = hierarchy.getMethod(methodSig);
            if (method != null) {
                int index = InvokeUtils.toInt(node.get("index").asText());
                Type type = typeSystem.getType(node.get("type").asText());
                return new ParamSource(method, index, type);
            } else {
                // if the method (given in config file) is absent in
                // the class hierarchy, just ignore it.
                logger.warn("Cannot find source method '{}'", methodSig);
                return null;
            }
        }

        /**
         * Deserializes a {@link JsonNode} (assume it is an {@link ArrayNode})
         * to a list of {@link Sink}.
         *
         * @param node the node to be deserialized
         * @return list of deserialized {@link Sink}
         */
        private List<Sink> deserializeSinks(JsonNode node) {
            if (node instanceof ArrayNode arrayNode) {
                List<Sink> sinks = new ArrayList<>(arrayNode.size());
                for (JsonNode elem : arrayNode) {
                    String methodSig = elem.get("method").asText();
                    JMethod method = hierarchy.getMethod(methodSig);
                    if (method != null) {
                        // if the method (given in config file) is absent in
                        // the class hierarchy, just ignore it.
                        int index = InvokeUtils.toInt(elem.get("index").asText());
                        sinks.add(new Sink(method, index));
                    } else {
                        logger.warn("Cannot find sink method '{}'", methodSig);
                    }
                }
                return Collections.unmodifiableList(sinks);
            } else {
                // if node is not an instance of ArrayNode, just return an empty set.
                return List.of();
            }
        }

        /**
         * Deserializes a {@link JsonNode} (assume it is an {@link ArrayNode})
         * to a list of {@link TaintTransfer}.
         *
         * @param node the node to be deserialized
         * @return list of deserialized {@link TaintTransfer}
         */
        private List<TaintTransfer> deserializeTransfers(JsonNode node) {
            if (node instanceof ArrayNode arrayNode) {
                List<TaintTransfer> transfers = new ArrayList<>(arrayNode.size());
                for (JsonNode elem : arrayNode) {
                    String methodSig = elem.get("method").asText();
                    JMethod method = hierarchy.getMethod(methodSig);
                    if (method != null) {
                        // if the method (given in config file) is absent in
                        // the class hierarchy, just ignore it.
                        int from = InvokeUtils.toInt(elem.get("from").asText());
                        int to = InvokeUtils.toInt(elem.get("to").asText());
                        Type type = typeSystem.getType(elem.get("type").asText());
                        transfers.add(new TaintTransfer(method, from, to, type));
                    } else {
                        logger.warn("Cannot find taint-transfer method '{}'", methodSig);
                    }
                }
                return Collections.unmodifiableList(transfers);
            } else {
                // if node is not an instance of ArrayNode, just return an empty set.
                return List.of();
            }
        }

        /**
         * Deserializes a {@link JsonNode} (assume it is an {@link ArrayNode})
         * to a list of {@link Sanitizer}.
         *
         * @param node the node to be deserialized
         * @return list of deserialized {@link Sanitizer}.
         */
        private List<ParamSanitizer> deserializeSanitizers(JsonNode node) {
            if (node instanceof ArrayNode arrayNode) {
                List<ParamSanitizer> sanitizers = new ArrayList<>(arrayNode.size());
                for (JsonNode elem : arrayNode) {
                    String methodSig = elem.get("method").asText();
                    JMethod method = hierarchy.getMethod(methodSig);
                    if (method != null) {
                        int index = InvokeUtils.toInt(elem.get("index").asText());
                        sanitizers.add(new ParamSanitizer(method, index));
                    } else {
                        logger.warn("Cannot find sanitizer method '{}'", methodSig);
                    }
                }
                return Collections.unmodifiableList(sanitizers);
            } else {
                // if node is not an instance of ArrayNode, just return an empty set.
                return List.of();
            }
        }
    }
}
