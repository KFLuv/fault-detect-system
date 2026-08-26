package com.cgn.faultdetect;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 知识库（92 场景 + 32 状态码 + 分类标签/配色）
 * 数据来源：src/main/resources/knowledge/*.json（由 Python 版导出，保证一致）
 */
@Component
public class Knowledge {

    // ================= POJO =================
    public static class Scenario {
        public String id;
        public String name;
        public List<String> http_codes;
        public List<String> response_patterns;
        public List<String> ui_symptoms;
        public String root_cause;
        public String conclusion;
        public List<String> evidence;
        public List<String> solution;
        public double probability;
        public String priority;
        /** 仅自定义场景输出该字段（与 Python 版一致：内置场景无 custom 键） */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Boolean custom;
    }

    public static class StatusCode {
        public String code;
        public String name;
        public String category;
        public List<String> problem_category;
        public String next_step;
        public String description;
        public String memory;
        public List<String> typical;
    }

    static class ScenariosFile {
        public Map<String, String> category_labels;
        public Map<String, String> category_colors;
        public List<Scenario> scenarios;
    }

    static class StatusCodesFile {
        public List<StatusCode> status_codes;
        public Map<String, StatusCode> status_code_map;
    }

    // ================= 加载后的数据 =================
    public final Map<String, String> categoryLabels;
    public final Map<String, String> categoryColors;
    /** 内置场景（检测匹配仅使用内置场景，与 Python 版行为一致） */
    public final List<Scenario> scenarios;
    public final List<StatusCode> statusCodes;
    public final Map<String, StatusCode> statusCodeMap;

    public Knowledge(ObjectMapper mapper) throws IOException {
        ScenariosFile sf = mapper.readValue(
                new ClassPathResource("knowledge/scenarios.json").getInputStream(), ScenariosFile.class);
        StatusCodesFile cf = mapper.readValue(
                new ClassPathResource("knowledge/status_codes.json").getInputStream(), StatusCodesFile.class);
        this.categoryLabels = sf.category_labels;
        this.categoryColors = sf.category_colors;
        this.scenarios = sf.scenarios;
        this.statusCodes = cf.status_codes;
        this.statusCodeMap = cf.status_code_map;
    }
}
