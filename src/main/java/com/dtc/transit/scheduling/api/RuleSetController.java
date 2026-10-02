package com.dtc.transit.scheduling.api;

import java.net.URI;
import java.time.LocalDate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dtc.transit.common.paging.PageResponse;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.rules.RuleSetService;
import com.dtc.transit.scheduling.rules.StoredRuleSet;

/**
 * The labour and operational rules, as data a client can read and version.
 *
 * <p>The whole point of Phase 7 is that these are configuration. Changing the maximum continuous work here
 * changes what the scheduler produces, with nothing recompiled and nothing redeployed.
 */
@RestController
@RequestMapping("/api/v1/rule-sets")
public class RuleSetController {

    private final RuleSetService ruleSetService;

    public RuleSetController(RuleSetService ruleSetService) {
        this.ruleSetService = ruleSetService;
    }

    @GetMapping
    public PageResponse<RuleSetResponse> list(@PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(ruleSetService.list(pageable), RuleSetResponse::summary);
    }

    /** One rule set with its values, which is what a client needs in order to edit it. */
    @GetMapping("/{id}")
    public RuleSetResponse get(@PathVariable Long id) {
        return RuleSetResponse.full(ruleSetService.require(id), ruleSetService.rulesOf(id));
    }

    /**
     * Which rule set a depot-day would be scheduled against.
     *
     * <p>Resolution has a precedence — a depot-specific set beats the global one, and the latest effective date
     * on or before the service date wins — and this is how a scheduler checks it rather than inferring it.
     */
    @GetMapping("/effective")
    public RuleSetResponse effective(
            @RequestParam Long depotId, @RequestParam LocalDate serviceDate) {
        var resolved = ruleSetService.resolve(depotId, serviceDate);
        return RuleSetResponse.full(ruleSetService.require(resolved.ruleSetId()), resolved.rules());
    }

    @PostMapping
    public ResponseEntity<RuleSetResponse> create(@Valid @RequestBody CreateRuleSetRequest request) {
        StoredRuleSet created = ruleSetService.create(
                request.name(), request.depotId(), request.effectiveFrom(), request.rules());
        return ResponseEntity.created(URI.create("/api/v1/rule-sets/" + created.getId()))
                .body(RuleSetResponse.full(created, request.rules()));
    }

    /**
     * Supersedes a rule set with a new version.
     *
     * <p>PUT, because that is how a client thinks about changing the rules, but nothing is overwritten: the
     * response is a new rule set with its own id and effective date, and the old one stays readable for the
     * schedules built under it.
     */
    @PutMapping("/{id}")
    public ResponseEntity<RuleSetResponse> replace(
            @PathVariable Long id, @Valid @RequestBody ReplaceRuleSetRequest request) {
        StoredRuleSet created = ruleSetService.replace(id, request.effectiveFrom(), request.rules());
        return ResponseEntity.created(URI.create("/api/v1/rule-sets/" + created.getId()))
                .body(RuleSetResponse.full(created, request.rules()));
    }

    /**
     * Checks a set of rules without storing it.
     *
     * <p>The interesting failures are contradictions between values, and an operator editing twenty-three numbers
     * deserves to find out here rather than from a run an hour later.
     */
    @PostMapping("/validate")
    public ValidationResponse validate(@Valid @RequestBody ValidateRuleSetRequest request) {
        ruleSetService.validate(request.rules());
        return new ValidationResponse(true, "The rule set is internally consistent");
    }

    /** @param depotId null for the global set, which is the fallback for every depot without one of its own */
    public record CreateRuleSetRequest(
            @NotBlank @Size(max = 200) String name,
            Long depotId,
            @NotNull LocalDate effectiveFrom,
            @NotNull @Valid RuleSet rules) {}

    public record ReplaceRuleSetRequest(@NotNull LocalDate effectiveFrom, @NotNull @Valid RuleSet rules) {}

    public record ValidateRuleSetRequest(@NotNull @Valid RuleSet rules) {}

    public record ValidationResponse(boolean valid, String message) {}

    /** @param rules null in a list response, where twenty-three numbers per row would be noise */
    public record RuleSetResponse(
            Long id, String name, Long depotId, boolean global, LocalDate effectiveFrom, long version, RuleSet rules) {

        static RuleSetResponse summary(StoredRuleSet stored) {
            return new RuleSetResponse(
                    stored.getId(),
                    stored.getName(),
                    stored.getDepotId(),
                    stored.isGlobal(),
                    stored.getEffectiveFrom(),
                    stored.getVersion(),
                    null);
        }

        static RuleSetResponse full(StoredRuleSet stored, RuleSet rules) {
            return new RuleSetResponse(
                    stored.getId(),
                    stored.getName(),
                    stored.getDepotId(),
                    stored.isGlobal(),
                    stored.getEffectiveFrom(),
                    stored.getVersion(),
                    rules);
        }
    }
}
