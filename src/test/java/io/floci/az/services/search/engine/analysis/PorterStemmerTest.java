package io.floci.az.services.search.engine.analysis;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/** The examples of Porter's paper and the definitive version's points of difference. */
class PorterStemmerTest {

    private static void stems(String word, String stem) {
        assertThat(word, PorterStemmer.stem(word), is(stem));
    }

    @Test
    void step1RemovesPluralsAndParticiples() {
        stems("caresses", "caress");
        stems("ponies", "poni");
        stems("ties", "ti");
        stems("caress", "caress");
        stems("cats", "cat");
        stems("feed", "feed");
        stems("plastered", "plaster");
        stems("bled", "bled");
        stems("motoring", "motor");
        stems("sing", "sing");
        stems("hopping", "hop");
        stems("falling", "fall");
        stems("hissing", "hiss");
        stems("fizzed", "fizz");
        stems("failing", "fail");
        stems("filing", "file");
        stems("happy", "happi");
        stems("sky", "sky");
    }

    @Test
    void complexSuffixesAreRemovedStepByStep() {
        stems("generalizations", "gener");
        stems("oscillators", "oscil");
        stems("relational", "relat");
        stems("conditional", "condit");
        stems("rational", "ration");
        stems("connection", "connect");
        stems("connected", "connect");
        stems("connecting", "connect");
        stems("adjustable", "adjust");
        stems("replacement", "replac");
        stems("adoption", "adopt");
    }

    @Test
    void onlyTheLongestMatchingSuffixIsConsidered() {
        stems("argument", "argument");
    }

    @Test
    void theDefinitiveVersionsDifferencesApply() {
        stems("possibly", "possibl");
        stems("archaeology", "archaeolog");
        stems("is", "is");
        stems("as", "as");
    }
}
