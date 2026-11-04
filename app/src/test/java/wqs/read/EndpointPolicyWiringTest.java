package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import wqs.read.cc.CcHighLevelAssembly;
import wqs.read.cc.CcHighLevelQuery;
import wqs.read.cc.CcLevel0Query;
import wqs.read.cc.CcTransactionMapper;
import wqs.read.web.WebHighLevelAssembly;
import wqs.read.web.WebHighLevelQuery;
import wqs.read.web.WebLevel0Query;
import wqs.read.web.WebTransactionMapper;

/**
 * Final-review M2: every /web read class is wired with /web's policy and every /cc class with /cc's,
 * checked against source sets written out here by hand -- not against {@link ReadPolicy#WEB} /
 * {@link ReadPolicy#CC} -- so swapping or widening a constant fails here instead of moving the
 * expectation along with it. ReadPolicyGuardTest checks the same on the source text.
 */
class EndpointPolicyWiringTest {

    /** TRANSACTION and CLAIMABLE_PRIZE, players only, no zero-amount rows. */
    private static final short[] WEB_SOURCES = {1, 3};
    /** Every source, players only, no zero-amount rows. */
    private static final short[] CC_SOURCES = {1, 2, 3, 4, 5};

    private record Wired(String name, ViewKind view, ReadPolicy policy) {}

    private static List<Wired> wired() {
        WebTransactionMapper web = new WebTransactionMapper();
        CcTransactionMapper cc = new CcTransactionMapper();
        WebLevel0Query webLevel0 = new WebLevel0Query(web);
        WebHighLevelQuery webHighLevel = new WebHighLevelQuery();
        WebHighLevelAssembly webAssembly = new WebHighLevelAssembly(web);
        CcLevel0Query ccLevel0 = new CcLevel0Query(cc);
        CcHighLevelQuery ccHighLevel = new CcHighLevelQuery();
        CcHighLevelAssembly ccAssembly = new CcHighLevelAssembly(cc);
        return List.of(
                new Wired("WebLevel0Query", webLevel0.view(), webLevel0.policy()),
                new Wired("WebHighLevelQuery", webHighLevel.view(), webHighLevel.policy()),
                new Wired("WebHighLevelAssembly", webAssembly.view(), webAssembly.policy()),
                new Wired("CcLevel0Query", ccLevel0.view(), ccLevel0.policy()),
                new Wired("CcHighLevelQuery", ccHighLevel.view(), ccHighLevel.policy()),
                new Wired("CcHighLevelAssembly", ccAssembly.view(), ccAssembly.policy()));
    }

    @Test
    void every_web_class_holds_the_web_sources_and_every_cc_class_the_cc_sources() {
        for (Wired w : wired()) {
            boolean web = w.name().startsWith("Web");
            assertThat(w.view()).as(w.name()).isEqualTo(web ? ViewKind.WEB : ViewKind.CC);
            assertThat(w.policy().sourceCodes()).as(w.name() + " sources").containsExactly(web ? WEB_SOURCES : CC_SOURCES);
            assertThat(w.policy().ownerType()).as(w.name() + " owner type").isEqualTo((short) 1);
            assertThat(w.policy().includeZeroAmounts()).as(w.name() + " zero amounts").isFalse();
        }
    }

    /** The runtime guard the reader and the assembler apply: a class holding the other endpoint's policy fails. */
    @Test
    void a_class_holding_the_other_endpoints_policy_is_refused() {
        ReadPolicy.requireOwn(ViewKind.WEB, ReadPolicy.WEB, this);
        ReadPolicy.requireOwn(ViewKind.CC, ReadPolicy.CC, this);
        assertThatThrownBy(() -> ReadPolicy.requireOwn(ViewKind.WEB, ReadPolicy.CC, this))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("WEB");
        assertThatThrownBy(() -> ReadPolicy.requireOwn(ViewKind.CC, ReadPolicy.WEB, this))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CC");
    }
}
