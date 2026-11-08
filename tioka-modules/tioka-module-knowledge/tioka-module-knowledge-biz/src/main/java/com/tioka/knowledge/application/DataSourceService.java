package com.tioka.knowledge.application;

import com.tioka.framework.id.Ids;

import com.tioka.connector.api.spi.FeishuDocContentSpi;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.knowledge.api.DataSourceApi;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.persistence.KbDatasourceDO;
import com.tioka.system.api.spi.EgressClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class DataSourceService implements DataSourceApi {
    private final KnowledgeService knowledgeService;
    private final KnowledgeRepository repo;
    private final EgressClient egress;
    private final ObjectProvider<FeishuDocContentSpi> feishuContent;

    public DataSourceService(KnowledgeService knowledgeService, KnowledgeRepository repo,
                             EgressClient egress,
                             ObjectProvider<FeishuDocContentSpi> feishuContent) {
        this.knowledgeService = knowledgeService;
        this.repo = repo;
        this.egress = egress;
        this.feishuContent = feishuContent;
    }

    @Override
    public DataSourceView bind(String kbId, String type, String uri) {
        String id = Ids.newId("ds");
        String t = type == null || type.isBlank() ? "upload" : type;
        DataSourceView ds = new DataSourceView(id, kbId, t, uri, "BOUND");
        KbDatasourceDO row = new KbDatasourceDO();
        row.setId(ds.id());
        row.setKbId(ds.kbId());
        row.setType(ds.type());
        row.setUri(ds.uri());
        row.setStatus(ds.status());
        repo.insertDatasource(row);
        return ds;
    }

    public List<DataSourceView> list(String kbId) {
        return repo.listDatasources(kbId).stream()
                .map(r -> new DataSourceView(r.getId(), r.getKbId(), r.getType(), r.getUri(), r.getStatus()))
                .toList();
    }

    /** 兼容旧接口：直接带 content 拉取入库。 */
    public String syncPull(String dsId, String title, String content) {
        return sync(dsId, Map.of(
                "title", title == null ? "sync" : title,
                "content", content == null ? "" : content));
    }

    /**
     * 按数据源类型同步：
     * - upload：使用 body content
     * - web：HTTP GET uri（EgressGuard + 短超时）
     * - feishu：FeishuDocContentSpi.rawContent(uri)
     */
    public String sync(String dsId, Map<String, String> body) {
        KbDatasourceDO row = repo.findDatasource(dsId);
        if (row == null) throw new BizException(ErrorCode.NOT_FOUND, "数据源不存在");
        DataSourceView ds = new DataSourceView(row.getId(), row.getKbId(), row.getType(), row.getUri(), row.getStatus());
        String title = body == null ? "sync" : body.getOrDefault("title", "sync");
        String type = ds.type() == null ? "upload" : ds.type().toLowerCase();
        String content = switch (type) {
            case "web" -> fetchWeb(ds.uri());
            case "feishu" -> fetchFeishu(ds.uri(), body);
            default -> body == null ? "" : body.getOrDefault("content", "");
        };
        var doc = knowledgeService.ingest(ds.kbId(), title, content, ds.type());
        return doc.id();
    }

    private String fetchWeb(String url) {
        if (url == null || url.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "web 数据源缺少 url");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = egress.send(req);
            if (resp.statusCode() >= 300) {
                throw new BizException(ErrorCode.BAD_REQUEST, "web sync HTTP " + resp.statusCode());
            }
            return resp.body() == null ? "" : resp.body();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "web sync 失败: " + e.getMessage());
        }
    }

    private String fetchFeishu(String tokenOrUri, Map<String, String> body) {
        FeishuDocContentSpi spi = feishuContent.getIfAvailable();
        if (spi != null && tokenOrUri != null && !tokenOrUri.isBlank()) {
            String token = tokenOrUri.startsWith("feishu://") ? tokenOrUri.substring("feishu://".length()) : tokenOrUri;
            String text = spi.rawContent(token);
            if (text != null && !text.isBlank()) return text;
        }
        return body == null ? "" : body.getOrDefault("content", "");
    }

}
