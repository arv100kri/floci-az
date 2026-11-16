package io.floci.az.services.search.skills;

import java.util.ArrayList;
import java.util.List;

/**
 * The order skills run in. A skill runs after every skill that writes a node it reads: its context, an input path, a
 * path in an input expression or a source context at or below an output of the other skill. Skills that do not depend
 * on each other keep their order in the definition, so the order is deterministic.
 */
final class SkillOrder {

    private SkillOrder() {
    }

    /** The skills in an order that respects their dependencies; skills caught in a cycle follow in definition order. */
    static List<SkillDefinition> order(List<SkillDefinition> skills) {
        int count = skills.size();
        boolean[][] dependsOn = dependencies(skills);
        List<SkillDefinition> ordered = new ArrayList<>(count);
        boolean[] placed = new boolean[count];
        boolean progress = true;
        while (ordered.size() < count && progress) {
            progress = false;
            for (int candidate = 0; candidate < count; candidate++) {
                if (!placed[candidate] && ready(candidate, dependsOn, placed)) {
                    placed[candidate] = true;
                    ordered.add(skills.get(candidate));
                    progress = true;
                    break;
                }
            }
        }
        for (int index = 0; index < count; index++) {
            if (!placed[index]) {
                ordered.add(skills.get(index));
            }
        }
        return ordered;
    }

    /** The names of the skills caught in a dependency cycle; empty when there is none. */
    static List<String> cycle(List<SkillDefinition> skills) {
        List<SkillDefinition> ordered = order(skills);
        boolean[][] dependsOn = dependencies(skills);
        List<String> cyclic = new ArrayList<>();
        boolean[] placed = new boolean[skills.size()];
        for (SkillDefinition skill : ordered) {
            int index = skills.indexOf(skill);
            if (!ready(index, dependsOn, placed)) {
                cyclic.add(skill.name());
            }
            placed[index] = true;
        }
        return cyclic;
    }

    private static boolean ready(int candidate, boolean[][] dependsOn, boolean[] placed) {
        for (int other = 0; other < placed.length; other++) {
            if (dependsOn[candidate][other] && !placed[other]) {
                return false;
            }
        }
        return true;
    }

    private static boolean[][] dependencies(List<SkillDefinition> skills) {
        int count = skills.size();
        boolean[][] dependsOn = new boolean[count][count];
        for (int reader = 0; reader < count; reader++) {
            List<EnrichmentPath> reads = skills.get(reader).reads();
            for (int writer = 0; writer < count; writer++) {
                if (writer != reader && writes(skills.get(writer), reads)) {
                    dependsOn[reader][writer] = true;
                }
            }
        }
        return dependsOn;
    }

    private static boolean writes(SkillDefinition writer, List<EnrichmentPath> reads) {
        for (EnrichmentPath written : writer.writes()) {
            for (EnrichmentPath read : reads) {
                if (written.isPrefixOf(read)) {
                    return true;
                }
            }
        }
        return false;
    }
}
