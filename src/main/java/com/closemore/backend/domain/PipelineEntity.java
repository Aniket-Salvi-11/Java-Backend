package com.closemore.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the `pipelines` table from V1__init.sql. Global reference data, no RLS - see ProductEntity.
 *
 * <p><b>Stages_JSON is the first JSONB column mapped in this codebase.</b> A plain String field
 * would fail {@code ddl-auto: validate} at context startup, because Hibernate would expect a
 * varchar and find {@code jsonb (Types#OTHER)}. {@code @JdbcTypeCode(SqlTypes.JSON)} tells
 * Hibernate the column is JSON; PostgreSQLDialect maps that to jsonb.
 *
 * <p>It is kept as a raw String rather than a mapped object graph on purpose for Phase 1: the
 * stage list is consumed as-is by the frontend, and deserialising it into Java types would invent
 * a schema the database does not enforce. Parse it at the edge if a read path ever needs the
 * individual stages.
 *
 * <p>IF THIS FAILS VALIDATION, it is the only line in this batch that can: swap the annotation for
 * {@code @JdbcTypeCode(SqlTypes.OTHER)}, or as a last resort drop the annotation and add
 * {@code columnDefinition = "jsonb"} to the @Column. Everything else here maps text/numeric columns
 * exactly as ContactEntity already does.
 */
@Entity
@Table(name = "pipelines")
@Getter
@Setter
@NoArgsConstructor
public class PipelineEntity {

    @Id
    @Column(name = "Pipeline_ID", nullable = false, updatable = false)
    private String pipelineId;

    @Column(name = "Pipeline_Name", nullable = false)
    private String pipelineName;

    /** Raw JSON text. See class javadoc before changing this mapping. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "Stages_JSON", nullable = false)
    private String stagesJson;
}
