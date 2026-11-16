package io.floci.az.services.search.engine.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class PhoneticEncodersTest {

    @Test
    void soundexFollowsTheNationalArchivesRules() {
        assertThat(PhoneticEncoders.soundex("Washington"), is("W252"));
        assertThat(PhoneticEncoders.soundex("Lee"), is("L000"));
        assertThat(PhoneticEncoders.soundex("Gutierrez"), is("G362"));
        assertThat(PhoneticEncoders.soundex("Pfister"), is("P236"));
        assertThat(PhoneticEncoders.soundex("Jackson"), is("J250"));
        assertThat(PhoneticEncoders.soundex("Tymczak"), is("T522"));
        assertThat(PhoneticEncoders.soundex("Ashcraft"), is("A261"));
        assertThat(PhoneticEncoders.soundex("Smith"), is(PhoneticEncoders.soundex("Smyth")));
        assertThat(PhoneticEncoders.soundex("1234"), is(""));
    }

    @Test
    void metaphoneGroupsWordsThatSoundAlike() {
        assertThat(PhoneticEncoders.metaphone("Smith"), is("SM0"));
        assertThat(PhoneticEncoders.metaphone("knight"), is(PhoneticEncoders.metaphone("night")));
        assertThat(PhoneticEncoders.metaphone("phone"), is(PhoneticEncoders.metaphone("fone")));
        assertThat(PhoneticEncoders.metaphone("Catherine"), is(PhoneticEncoders.metaphone("Kathryn")));
        assertThat(PhoneticEncoders.metaphone("Xavier").charAt(0), is('S'));
    }

    @Test
    void thePhoneticFilterReplacesOrAddsCodes() {
        AnalysisComponents components = AnalysisComponents.of(null);
        Analyzer replace = new ComposedAnalyzer("p", Tokenizers.whitespace(),
            components.tokenFilters(List.of("phonetic")));
        assertThat(replace.analyze("Smith").get(0).term(), is("SM0"));
    }
}
