package com.closemore.backend.service;

import com.closemore.backend.domain.DealEntity;
import com.closemore.backend.domain.PipelineEntity;
import com.closemore.backend.dto.PipelineCreateRequest;
import com.closemore.backend.dto.PipelineResponse;
import com.closemore.backend.dto.PipelineUpdateRequest;
import com.closemore.backend.mapper.DtoMapper;
import com.closemore.backend.rbac.AuthenticatedUser;
import com.closemore.backend.rbac.CurrentUserService;
import com.closemore.backend.rbac.RbacService;
import com.closemore.backend.rbac.Role;
import com.closemore.backend.repository.DealRepository;
import com.closemore.backend.repository.PipelineRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pipelines - five endpoints, and the only real logic in tranche 6.
 *
 * <p><b>Global reference data with no RLS</b>, same as products: the Admin check is the only
 * protection, not defence in depth. See ProductService.
 *
 * <p>The interesting endpoint is update, which can move deals. Read its javadoc before changing
 * anything here.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class PipelineService {

    private static final String OBJECT_TYPE = "Pipeline";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PipelineRepository pipelineRepository;
    private final DealRepository dealRepository;
    private final RbacService rbacService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    // --- reads ------------------------------------------------------------------------------

    /**
     * GET /api/v1/pipelines
     *
     * <p>Always a bare array, never paginated. A pipeline configuration is a handful of rows that
     * every deal screen needs in full; paging it would mean a client could render a stage picker
     * missing its last stage.
     */
    public List<PipelineResponse> listAll() {
        currentUserService.require();
        return pipelineRepository.findAll(Sort.by("pipelineName")).stream()
                .map(DtoMapper::toPipelineResponse)
                .toList();
    }

    /** GET /api/v1/pipelines/{pipelineId} - added by the same decision that added it to Contacts. */
    public PipelineResponse get(String pipelineId) {
        currentUserService.require();
        return DtoMapper.toPipelineResponse(load(pipelineId));
    }

    // --- writes -----------------------------------------------------------------------------

    /** POST /api/v1/pipelines - Admin only. */
    public PipelineResponse create(PipelineCreateRequest request) {
        AuthenticatedUser actor = requireAdmin();
        List<String> stages = parseStageNames(request.stagesJson());
        if (stages.isEmpty()) {
            throw new BadRequestException("A pipeline needs at least one stage");
        }

        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setPipelineId(UUID.randomUUID().toString());
        pipeline.setPipelineName(request.pipelineName());
        pipeline.setStagesJson(request.stagesJson());

        PipelineEntity saved = pipelineRepository.save(pipeline);
        PipelineResponse response = DtoMapper.toPipelineResponse(saved);
        auditService.logCreate(actor, OBJECT_TYPE, saved.getPipelineId(),
                saved.getPipelineName(), response);
        return response;
    }

    /**
     * PUT /api/v1/pipelines/{pipelineId} - Admin only. Reassigns deals on stage rename or removal.
     *
     * <p><b>How deals are moved.</b> {@code Current_Stage} on deals is a stage NAME, not an id, so
     * changing the stage list can leave deals pointing at a name that no longer exists. Two passes:
     *
     * <ol>
     *   <li><b>Rename by position.</b> If old stage {@code i} and new stage {@code i} both exist and
     *       the names differ, deals on the old name move to the new one. Position is the only signal
     *       available - the JSON carries no stable stage id - so reordering a list is
     *       indistinguishable from renaming several stages at once. CONFIRM against the JS
     *       behaviour; this is the assumption most likely to be wrong.</li>
     *   <li><b>Orphan sweep.</b> Any deal still on a name absent from the new list moves to the
     *       FIRST stage. That is a choice, not a rule from anywhere: it is the only destination
     *       guaranteed to exist, and moving a deal backwards is recoverable in a way that deleting
     *       its stage is not.</li>
     * </ol>
     *
     * <p><b>Renames are only detected when the list length is unchanged.</b> See reassignDeals -
     * on a shrinking list, positions stop lining up and a positional pass would read a removal as a
     * rename, moving open deals into whatever stage happens to sit at that index. Note the one edge
     * this does not guard: an Admin renaming a stage TO "Closed Won" moves live deals onto a
     * terminal stage name without changing their Status. That is an explicit act rather than a
     * side effect, so it is left alone - but it is worth knowing about.
     *
     * <p><b>Closed deals are never moved.</b> A deal on "Closed Won" or "Closed Lost" is terminal.
     * Sweeping it to the first stage would silently reopen closed business and, through
     * DealStageRules.statusForStage, change its status - so an Admin tidying a stage list would
     * resurrect last quarter's closed deals. Terminal deals keep their stage even when that stage is
     * removed from the pipeline, which is deliberately a dangling reference: a wrong historical
     * record is worse than an unresolvable one.
     *
     * <p><b>This reassignment is tenant-scoped, and the pipeline is not.</b> Pipelines have no RLS,
     * so an Admin in one organisation edits a stage list every organisation uses - but the deal
     * updates below run through DealRepository under the caller's RLS context, so only THEIR
     * organisation's deals move. Other tenants' deals keep the old stage name. This diverges from
     * the JS backend, which has no RLS and would update everyone's. Fixing it properly means a
     * SECURITY DEFINER writer that crosses tenants, which is a security decision nobody has taken -
     * so this does the conservative thing and never writes another tenant's rows. Raised as an open
     * item in docs/HANDOFF.md.
     */
    public PipelineResponse update(String pipelineId, PipelineUpdateRequest request) {
        AuthenticatedUser actor = requireAdmin();
        PipelineEntity pipeline = load(pipelineId);
        PipelineResponse before = DtoMapper.toPipelineResponse(pipeline);

        if (request.pipelineName() != null) {
            pipeline.setPipelineName(request.pipelineName());
        }

        if (request.stagesJson() != null) {
            List<String> oldStages = parseStageNames(pipeline.getStagesJson());
            List<String> newStages = parseStageNames(request.stagesJson());
            if (newStages.isEmpty()) {
                throw new BadRequestException("A pipeline needs at least one stage");
            }
            pipeline.setStagesJson(request.stagesJson());
            reassignDeals(pipelineId, oldStages, newStages);
        }

        PipelineEntity saved = pipelineRepository.save(pipeline);
        PipelineResponse after = DtoMapper.toPipelineResponse(saved);
        auditService.logUpdate(actor, OBJECT_TYPE, pipelineId, saved.getPipelineName(),
                before, after);
        return after;
    }

    /**
     * DELETE /api/v1/pipelines/{pipelineId} - Admin only, blocked if any deal uses it.
     *
     * <p><b>No application-level "is it in use" check, on purpose.</b> The obvious implementation -
     * count deals on this pipeline and refuse if non-zero - would count only the CALLER'S deals,
     * because deals are under RLS and pipelines are not. A pipeline used solely by another
     * organisation would pass that check and then fail at the foreign key anyway. Letting the FK
     * decide is both simpler and the only version that is actually correct across tenants:
     * Deal_Pipeline_ID is ON DELETE RESTRICT, and ApiExceptionHandler already turns the resulting
     * DataIntegrityViolationException into a 409.
     */
    public void delete(String pipelineId) {
        AuthenticatedUser actor = requireAdmin();
        PipelineEntity pipeline = load(pipelineId);
        PipelineResponse before = DtoMapper.toPipelineResponse(pipeline);
        String name = pipeline.getPipelineName();

        pipelineRepository.delete(pipeline);
        // Flush inside the transaction so the FK fires here rather than at commit, where the
        // exception would escape the handler's DataIntegrityViolationException mapping.
        pipelineRepository.flush();

        auditService.logDelete(actor, OBJECT_TYPE, pipelineId, name, before);
    }

    // --- the cascade -------------------------------------------------------------------------

    private void reassignDeals(String pipelineId, List<String> oldStages, List<String> newStages) {
        // Pass 1: positional renames - ONLY when the list length is unchanged.
        //
        // The length check is load-bearing. Position is the only rename signal available, and on a
        // SHRINKING list positions no longer line up: dropping "Proposal" from
        // [Discovery, Proposal, Closed Won] leaves [Discovery, Closed Won], and a naive positional
        // pass reads index 1 as "Proposal renamed to Closed Won" - silently moving live deals into
        // a terminal stage. Same length means every stage still has a counterpart, which is the
        // only case where position is trustworthy. Anything else is handled as add/remove by the
        // sweep below.
        if (oldStages.size() == newStages.size()) {
            for (int i = 0; i < oldStages.size(); i++) {
                String oldName = oldStages.get(i);
                String newName = newStages.get(i);
                if (!oldName.equals(newName)) {
                    moveDeals(pipelineId, oldName, newName);
                }
            }
        }

        // Pass 2: anything still pointing at a stage that no longer exists.
        String firstStage = newStages.get(0);
        for (String oldName : oldStages) {
            if (!newStages.contains(oldName)) {
                moveDeals(pipelineId, oldName, firstStage);
            }
        }
    }

    private void moveDeals(String pipelineId, String fromStage, String toStage) {
        List<DealEntity> deals =
                dealRepository.findByPipelineIdAndCurrentStage(pipelineId, fromStage);

        for (DealEntity deal : deals) {
            // See the class javadoc: closed business is never reopened by a pipeline edit.
            if (DealStageRules.isTerminal(deal.getCurrentStage())) {
                continue;
            }
            deal.setCurrentStage(toStage);
            dealRepository.save(deal);
        }
    }

    // --- helpers ----------------------------------------------------------------------------

    /**
     * Reads stage names out of the raw JSON, in order.
     *
     * <p>Parsed at the edge rather than mapped into an entity graph - PipelineEntity keeps the
     * column as a raw String on purpose, because the database enforces no schema for it and a
     * mapped type would be a second, competing definition of a stage.
     */
    private static List<String> parseStageNames(String stagesJson) {
        JsonNode root;
        try {
            root = JSON.readTree(stagesJson);
        } catch (Exception malformed) {
            throw new BadRequestException("stagesJson is not valid JSON");
        }
        if (!root.isArray()) {
            throw new BadRequestException("stagesJson must be a JSON array of stages");
        }

        List<String> names = new ArrayList<>();
        for (JsonNode stage : root) {
            JsonNode name = stage.get("name");
            if (name == null || !name.isTextual() || name.asText().isBlank()) {
                throw new BadRequestException("Every stage needs a non-empty \"name\"");
            }
            names.add(name.asText());
        }
        return names;
    }

    private AuthenticatedUser requireAdmin() {
        AuthenticatedUser actor = currentUserService.require();
        rbacService.requireRole(actor, Role.ADMIN);
        return actor;
    }

    private PipelineEntity load(String pipelineId) {
        return pipelineRepository.findById(pipelineId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Pipeline " + pipelineId + " not found"));
    }
}
