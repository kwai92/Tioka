package com.tioka.model.application.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.governance.api.config.ConfigVersionApi.ConfigDraft;
import com.tioka.model.api.dto.PromptDtos.CreatePromptTemplateRequest;
import com.tioka.model.api.dto.PromptDtos.PromptTemplateView;
import com.tioka.model.api.dto.PromptDtos.RenderResultView;
import com.tioka.model.api.dto.PromptDtos.RenderedSectionView;
import com.tioka.model.api.dto.PromptDtos.TestRunPromptRequest;
import com.tioka.model.api.dto.PromptDtos.TestRunResultView;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.api.prompt.PromptRenderEngine;
import com.tioka.model.api.prompt.PromptRenderException;
import com.tioka.model.api.prompt.PromptTemplateSpec;
import com.tioka.model.api.prompt.PromptTemplateValidator;
import com.tioka.model.api.versioning.ModelHubSpecs;
import com.tioka.model.application.versioning.ModelConfigVersionService;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.domain.ModelEntities.ModelAlias;
import com.tioka.model.infrastructure.ModelEntitiesRepository;
import com.tioka.model.infrastructure.metrics.ModelMetrics;
import com.tioka.model.infrastructure.persistence.PromptTemplateDO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AST-07（ADR-041 §1 / §4）：Prompt 模板资产。
 *
 * <p>内容（分段正文 / 变量定义 / 用途 / 输出格式 / 建议别名 / 样例变量）作为发布型对象
 * 进 GOV-12（objectType={@code prm_template}，草稿 / 发布 / 回滚复用
 * {@code /api/model/configs/prm_template/{id}/*}）；本服务负责身份行、发布期校验、
 * 运行期渲染与编辑器试运行。
 *
 * <p>试运行经 {@link ModelGateway}（用途 {@link ModelPurpose#PROMPT_TEST}），
 * 别名须指向路由计划；模型调用不在请求线程之外自行实现，沿用统一治理卡点。
 */
@Service
public class PromptTemplateService {

    private final ModelEntitiesRepository repo;
    private final ModelEntities entities;
    private final ModelConfigVersionService versioning;
    private final ObjectMapper mapper;
    private final PrincipalHolder principals;
    private final ObjectProvider<ModelGateway> gateway;
    private final ModelMetrics metrics;

    public PromptTemplateService(ModelEntitiesRepository repo, ModelEntities entities,
                                 ModelConfigVersionService versioning, ObjectMapper mapper,
                                 PrincipalHolder principals, ObjectProvider<ModelGateway> gateway,
                                 ModelMetrics metrics) {
        this.repo = repo;
        this.entities = entities;
        this.versioning = versioning;
        this.mapper = mapper;
        this.principals = principals;
        this.gateway = gateway;
        this.metrics = metrics;
    }

    // ---------------------------------------------------------------- 查询

    public List<PromptTemplateView> list() {
        List<PromptTemplateView> out = new ArrayList<>();
        for (PromptTemplateDO row : repo.promptTemplates()) {
            out.add(view(row));
        }
        return out;
    }

    public PromptTemplateView get(String id) {
        return view(require(id));
    }

    // ---------------------------------------------------------------- 身份

    /** 创建模板身份；{@code publish=true} 时同时登记并发布第 1 版。 */
    public PromptTemplateView create(CreatePromptTemplateRequest req) {
        PromptTemplateSpec spec = parse(req == null ? null : req.spec());
        requireValid(spec);
        String name = spec.name().trim();
        if (repo.findPromptTemplateByName(name).isPresent()) {
            throw new BizException(ErrorCode.CONFLICT, "Prompt 模板已存在: " + name);
        }
        boolean publish = req != null && Boolean.TRUE.equals(req.publish());
        if (publish && !versioning.available()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "配置版本框架未启用（tioka.config.version.enabled），无法发布模板");
        }
        String id = Ids.newId("prmtpl");
        PromptTemplateDO row = new PromptTemplateDO();
        row.setId(id);
        row.setName(name);
        row.setScope(PromptTemplateSpec.normalizeScope(spec.scope()));
        row.setScopeRef(spec.scopeRef());
        repo.putPromptTemplate(row);
        if (publish) {
            versioning.saveDraft(ModelHubSpecs.OT_PROMPT_TEMPLATE, id, spec.toJson(mapper), 0L);
            versioning.publishDraft(ModelHubSpecs.OT_PROMPT_TEMPLATE, id, "创建 Prompt 模板第 1 版");
        }
        return get(id);
    }

    /** 保存草稿（发布期校验提前到草稿保存，便于编辑器即时反馈）。 */
    public ConfigDraft saveDraft(String id, JsonNode spec, Long expectedRevision) {
        PromptTemplateDO row = require(id);
        PromptTemplateSpec parsed = parse(spec);
        requireValid(parsed);
        if (!row.getName().equals(parsed.name())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模板名称不可变更: " + parsed.name());
        }
        if (!versioning.available()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "配置版本框架未启用（tioka.config.version.enabled），无法保存草稿");
        }
        return versioning.saveDraft(ModelHubSpecs.OT_PROMPT_TEMPLATE, id, parsed.toJson(mapper),
                        expectedRevision == null ? 0L : expectedRevision)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "草稿保存失败"));
    }

    // ---------------------------------------------------------------- 渲染

    /** 渲染（无模型调用）：按当前生效版本用给定变量渲染分段。 */
    public RenderResultView render(String id, Map<String, Object> variables) {
        PromptTemplateSpec spec = currentSpec(id);
        if (spec == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Prompt 模板无生效版本: " + id);
        }
        try {
            List<RenderedSectionView> sections = PromptRenderEngine.render(spec, variables).stream()
                    .map(s -> new RenderedSectionView(s.role(), s.body()))
                    .toList();
            return new RenderResultView(spec.name(), sections);
        } catch (PromptRenderException e) {
            metrics.recordPromptRenderError(spec.name());
            throw new BizException(ErrorCode.PROMPT_RENDER_ERROR, e.getMessage());
        }
    }

    /** 试运行：渲染后经统一模型网关调用（用途 PROMPT_TEST），别名须指向路由计划。 */
    public TestRunResultView testRun(String id, TestRunPromptRequest req) {
        RenderResultView rendered = render(id, req == null ? null : req.variables());
        ModelGateway gw = gateway == null ? null : gateway.getIfAvailable();
        if (gw == null) {
            throw new BizException(ErrorCode.DEPENDENCY, "模型网关未装配");
        }
        PromptTemplateSpec spec = currentSpec(id);
        String aliasName = blank(req == null ? null : req.aliasName())
                ? (spec == null ? null : spec.suggestedAlias()) : req.aliasName();
        String planId = resolvePlanId(aliasName);
        Principal principal = principals == null ? null : principals.current();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (RenderedSectionView s : rendered.sections()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", s.role());
            m.put("content", s.body() == null ? "" : s.body());
            messages.add(m);
        }
        if (req != null && !blank(req.userInput())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", "user");
            m.put("content", req.userInput());
            messages.add(m);
        }
        ModelGateway.ChatResult result = gw.chat(
                new ModelCallContext(principal, null, null, null, planId, ModelPurpose.PROMPT_TEST),
                new ModelGateway.ChatRequest(messages, List.of(), null, null), null);
        return new TestRunResultView(rendered.name(), rendered.sections(), result.bindingId(),
                result.modelRef(), result.content(), result.promptTokens(), result.completionTokens());
    }

    // ---------------------------------------------------------------- helpers

    private PromptTemplateDO require(String id) {
        return repo.getPromptTemplate(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Prompt 模板不存在: " + id));
    }

    private PromptTemplateView view(PromptTemplateDO row) {
        Optional<com.tioka.governance.api.config.ConfigVersionApi.ConfigVersion> current =
                versioning.current(ModelHubSpecs.OT_PROMPT_TEMPLATE, row.getId());
        JsonNode spec = current.map(com.tioka.governance.api.config.ConfigVersionApi.ConfigVersion::spec)
                .orElse(null);
        return new PromptTemplateView(row.getId(), row.getName(), row.getScope(), row.getScopeRef(),
                current.map(com.tioka.governance.api.config.ConfigVersionApi.ConfigVersion::versionNo).orElse(null),
                current.isPresent() ? "PUBLISHED" : "NO_VERSION",
                spec);
    }

    /** 当前生效版本的模板 spec；无生效版本返回 null。 */
    private PromptTemplateSpec currentSpec(String id) {
        require(id);
        return versioning.current(ModelHubSpecs.OT_PROMPT_TEMPLATE, id)
                .map(v -> PromptTemplateSpec.fromJson(mapper, v.spec()))
                .orElse(null);
    }

    private PromptTemplateSpec parse(JsonNode spec) {
        PromptTemplateSpec parsed = PromptTemplateSpec.fromJson(mapper, spec);
        if (parsed == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "spec 必须是 JSON 对象");
        }
        return parsed;
    }

    private static void requireValid(PromptTemplateSpec spec) {
        List<PromptTemplateValidator.Issue> issues = PromptTemplateValidator.validate(spec);
        if (!PromptTemplateValidator.ok(issues)) {
            String first = issues.stream()
                    .filter(i -> PromptTemplateValidator.SEVERITY_ERROR.equals(i.severity()))
                    .map(PromptTemplateValidator.Issue::message)
                    .findFirst().orElse("校验失败");
            throw new BizException(ErrorCode.BAD_REQUEST, "Prompt 模板校验失败: " + first);
        }
    }

    private String resolvePlanId(String aliasName) {
        if (blank(aliasName)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未指定试运行别名（spec.suggestedAlias 为空）");
        }
        ModelAlias alias = entities.findAliasByName(aliasName)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "别名不存在: " + aliasName));
        if (ModelHubSpecs.TARGET_ROUTING_PLAN.equals(alias.targetType()) && !blank(alias.targetId())) {
            return alias.targetId();
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "试运行要求别名指向路由计划: " + aliasName);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
