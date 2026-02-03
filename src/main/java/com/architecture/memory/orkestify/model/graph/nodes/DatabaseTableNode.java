package com.architecture.memory.orkestify.model.graph.nodes;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import java.util.List;

@Node("DatabaseTable")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseTableNode {

    @Id
    @GeneratedValue(generatorClass = UUIDStringGenerator.class)
    private String id;

    @Property("tableName")
    private String tableName;

    @Property("entityClass")
    private String entityClass;

    @Property("entitySimpleName")
    private String entitySimpleName;

    @Property("databaseType")
    private String databaseType; // JPA, MongoDB, etc.

    @Property("tableSource")
    private String tableSource; // @Table, @Document, derived_from_class_name

    @Property("operations")
    private List<String> operations; // READ, WRITE, UPDATE, DELETE

    @Property("projectId")
    private String projectId;
}
