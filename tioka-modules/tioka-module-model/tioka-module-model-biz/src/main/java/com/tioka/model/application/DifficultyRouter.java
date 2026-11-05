package com.tioka.model.application;

import org.springframework.stereotype.Component;

@Component
public class DifficultyRouter {
    public enum Tier { NORMAL, HARD }

    public Tier decide(String userText) {
        if (userText == null) {
            return Tier.NORMAL;
        }
        String t = userText.toLowerCase();
        if (t.contains("复杂") || t.contains("hard") || t.length() > 500) {
            return Tier.HARD;
        }
        return Tier.NORMAL;
    }
}
