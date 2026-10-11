package com.agent.biz.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 评测集加载器：从 classpath:/eval/eval-cases.json 读取用例。
 */
@Component
public class EvalCaseLoader {

    private static final String PATH = "eval/eval-cases.json";

    private final ObjectMapper om;

    public EvalCaseLoader(ObjectMapper om) {
        this.om = om;
    }

    public List<EvalCase> load() {
        try (InputStream in = new ClassPathResource(PATH).getInputStream()) {
            return om.readValue(in, new TypeReference<List<EvalCase>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
