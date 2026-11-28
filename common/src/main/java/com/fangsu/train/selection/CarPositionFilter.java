package com.fangsu.train.selection;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** MTR3 车辆部件的车号规则，车位从 0 开始传入。 */
final class CarPositionFilter {
    private final List<Rule> whitelist;
    private final List<Rule> blacklist;
    private final int whitelistDefault;
    private final int blacklistDefault;

    CarPositionFilter(JsonObject item) {
        String[] white = read(item, "whitelisted_cars");
        String[] black = read(item, "blacklisted_cars");
        whitelist = parse(white);
        blacklist = parse(black);
        whitelistDefault = white.length == 0 ? 1 : 0;
        blacklistDefault = black.length == 0 ? 1 : 0;
    }

    boolean matches(int index, int count) {
        return strength(whitelist, whitelistDefault, index, count)
                >= strength(blacklist, blacklistDefault, index, count);
    }

    private static String[] read(JsonObject item, String key) {
        JsonElement value = item.get(key);
        return (value != null && value.isJsonPrimitive() ? value.getAsString() : "").split(",");
    }

    private static List<Rule> parse(String[] expressions) {
        List<Rule> rules = new ArrayList<>();
        for (String expression : expressions) {
            if (expression.isEmpty()) continue;
            try {
                if (expression.contains("%")) {
                    String[] parts = expression.split("\\+");
                    int period = Integer.parseInt(parts[0].replace("%", ""));
                    int offset = parts.length == 1 ? 0 : Integer.parseInt(parts[1]);
                    if (period != 0) rules.add(new Rule(0, period, offset));
                } else {
                    rules.add(new Rule(Integer.parseInt(expression), 0, 0));
                }
            } catch (NumberFormatException | IndexOutOfBoundsException ignored) {
                // 原版忽略无法解析的车号表达式。
            }
        }
        return List.copyOf(rules);
    }

    private static int strength(List<Rule> rules, int fallback, int index, int count) {
        int result = fallback;
        for (Rule rule : rules) {
            if (rule.period == 0) {
                if (rule.number == index + 1 || rule.number == index - count) return 3;
            } else if ((index + 1 + rule.offset) % rule.period == 0) {
                result = 2;
            }
        }
        return result;
    }

    private record Rule(int number, int period, int offset) {}
}
