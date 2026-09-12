package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** P10：ACID 后三/四尾选机（详细设计 2.2 §7.1：多匹配列候选且不改变选择）。 */
public class AcidSelectionService {

    /** 返回 {selected, candidates}：唯一匹配才选中；多匹配只列候选。 */
    public Map<String, Object> selectBySuffix(String suffix, List<String> callsigns) {
        String normalized = suffix == null ? "" : suffix.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9]{3,4}")) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "选机后缀必须是 3–4 位字母/数字: " + suffix, Arrays.asList("callsignSuffix"));
        }
        List<String> candidates = findCandidates(normalized, callsigns);
        Map<String, Object> result = new LinkedHashMap<>();
        if (candidates.size() == 1) {
            result.put("selected", candidates.get(0));
        } else {
            result.put("selected", null);
        }
        result.put("candidates", candidates);
        return result;
    }

    public List<String> findCandidates(String normalizedSuffix, List<String> callsigns) {
        List<String> candidates = new ArrayList<>();
        if (callsigns == null) {
            return candidates;
        }
        for (String callsign : callsigns) {
            if (callsign != null && callsign.toUpperCase().endsWith(normalizedSuffix)) {
                candidates.add(callsign.toUpperCase());
            }
        }
        return candidates;
    }
}
