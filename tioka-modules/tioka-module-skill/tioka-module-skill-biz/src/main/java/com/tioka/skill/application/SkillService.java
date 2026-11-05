package com.tioka.skill.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.skill.infrastructure.SkillPackageRepository;
import com.tioka.skill.infrastructure.persistence.SkillPackageDO;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class SkillService {
    private final SkillPackageRepository packages;

    public SkillService(SkillPackageRepository packages) {
        this.packages = packages;
    }

    public SkillPackage importZip(byte[] zipBytes) {
        Map<String, String> files = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                files.put(e.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (Exception ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "无效 ZIP: " + ex.getMessage());
        }
        String manifest = files.get("manifest.json");
        if (manifest == null || !manifest.contains("name")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少有效 manifest.json");
        }
        if (!files.containsKey("SKILL.md")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 SKILL.md");
        }
        String id = "sk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SkillPackage pkg = new SkillPackage(id, extractName(manifest), false, files.get("SKILL.md"));
        SkillPackageDO row = new SkillPackageDO();
        row.setId(pkg.id()); row.setName(pkg.name()); row.setPublished(pkg.published()); row.setSkillMd(pkg.skillMd());
        packages.insert(row);
        return pkg;
    }

    public List<SkillPackage> list() {
        return packages.listAll().stream()
                .map(r -> new SkillPackage(r.getId(), r.getName(), Boolean.TRUE.equals(r.getPublished()), r.getSkillMd()))
                .toList();
    }

    public SkillPackage publish(String id) {
        SkillPackageDO row = packages.findById(id);
        if (row == null) throw new BizException(ErrorCode.NOT_FOUND, "skill 不存在");
        row.setPublished(true);
        packages.update(row);
        return new SkillPackage(row.getId(), row.getName(), true, row.getSkillMd());
    }

    private String extractName(String manifest) {
        int i = manifest.indexOf("\"name\"");
        if (i < 0) return "unnamed";
        int c = manifest.indexOf(':', i);
        int q1 = manifest.indexOf('"', c + 1);
        int q2 = manifest.indexOf('"', q1 + 1);
        return manifest.substring(q1 + 1, q2);
    }

    public record SkillPackage(String id, String name, boolean published, String skillMd) {}
}
