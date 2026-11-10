package com.tioka.ontology.application.doc;

import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.model.api.capability.OcrRequest;
import com.tioka.model.api.capability.OcrResult;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.ontology.api.docparse.ParsedLine;
import com.tioka.ontology.api.docparse.ParsedRegion;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * OcrClient 生产实现：经 ModelGateway.ocr 调用（与 chat 同走配额 / 用量 / 审计 / 路由）。
 * purpose = OCR；主体缺失时用系统主体（解析为后台任务，无会话上下文）。
 */
public class ModelGatewayOcrClient implements OcrClient {

    private final ModelGateway gateway;
    private final PrincipalHolder principals;

    public ModelGatewayOcrClient(ModelGateway gateway, PrincipalHolder principals) {
        this.gateway = gateway;
        this.principals = principals;
    }

    @Override
    public List<ParsedLine> ocrPage(int pageNo, byte[] pagePng, String dataLabel, String spaceId) {
        Principal principal = principals.find().orElseGet(() ->
                new Principal("system", spaceId == null ? "" : spaceId,
                        PrincipalType.SERVICE, java.util.Set.of(), java.util.Set.of(), "system"));
        ModelCallContext ctx = new ModelCallContext(principal, null, null, null, null,
                ModelPurpose.OCR);
        OcrResult result = gateway.ocr(ctx, new OcrRequest(null, pagePng, dataLabel, spaceId));
        List<ParsedLine> lines = new ArrayList<>();
        for (OcrResult.OcrLine l : result.lines()) {
            ParsedRegion region = new ParsedRegion(pageNo, l.x0(), l.y0(), l.x1(), l.y1());
            lines.add(new ParsedLine(pageNo, l.text(), List.of(region), "PARA", null,
                    com.tioka.ontology.api.docparse.ParseContract.S_OCR, l.confidence()));
        }
        return lines;
    }
}
