package com.roottun.clash.core

import org.junit.Assert.*
import org.junit.Test
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode

class AppRoutingTest {
    private val policy = ResolvedAppRouting(listOf(
        AppRouteOwner("org.example.browser", 10123, AppRoute.PROXY),
        AppRouteOwner("org.example.direct", 10124, AppRoute.DIRECT),
    ))
    private val opts = RuntimeOptions(appRouting = policy)
    private fun rules(yaml: String) = (ClashYaml.parse(yaml).fields.getValue("rules") as SequenceNode).value.map { (it as ScalarNode).value }
    private fun compose(input: String) = ConfigComposer.compose(input.trimIndent(), opts)

    @Test fun groupThatCanSelectDirectNeverAuthorizesForcedProxy() {
        val original = "  - 'DOMAIN,example.test,Choice' # 原注释\n  - MATCH,DIRECT"
        val result = compose("proxies: [{name: alias, type: direct}, {type: socks5, name: Node, server: localhost, port: 1080}]\nproxy-groups: [{name: Choice, type: select, proxies: [DIRECT, Node]}]\nrules:\n$original\n")
        val actual = rules(result.yaml)
        assertTrue(result.yaml.contains(original))
        assertTrue(result.yaml.contains("find-process-mode: strict"))
        assertEquals(policy.prefix("Node") + listOf("DOMAIN,example.test,Choice", "MATCH,DIRECT"), actual)
        assertTrue(actual[0].contains("(NOT,((UID,1-4294967295)))"))
        assertEquals("UID,10123,Node", actual[1])
        assertEquals("UID,10123,REJECT", actual[2])
        assertTrue(actual[3].endsWith(",DIRECT"))
        assertFalse(result.yaml.contains("include-uid"))
        assertFalse(result.yaml.contains("exclude-uid"))
    }

    @Test fun noGroupsAddsDefaultSelectorButNotAsForcedTarget() {
        val config = compose("proxies:\n  - type: http\n    name: Remote\n    server: localhost\n    port: 3128\n")
        assertEquals(listOf("PROXY"), ClashYaml.parse(config.yaml).entries("proxy-groups").map { it.name })
        assertEquals(policy.prefix("Remote") + "MATCH,PROXY", rules(config.yaml))
    }

    @Test fun existingGroupWithoutRulesUsesItsRealName() {
        val config = compose("proxies: []\nproxy-groups:\n  - {name: Choice, type: select, proxies: [DIRECT]}\n")
        assertEquals(policy.prefix("REJECT") + "MATCH,Choice", rules(config.yaml))
    }

    @Test fun directSelfCheckIsIdempotentAndRejectsForcedProxy() {
        val once = ConfigComposer.directConfig(opts)
        val twice = ConfigComposer.compose(once, opts).yaml
        assertEquals(policy.prefix("REJECT") + "MATCH,DIRECT", rules(once))
        assertEquals(rules(once), rules(twice))
        assertEquals(listOf("MATCH,DIRECT"), rules(ConfigComposer.directConfig(RuntimeOptions())))
    }

    @Test fun flowRulesAndIndentlessRulesArePreserved() {
        for (rules in listOf("'rules': ['MATCH,DIRECT'] # 注释", "rules:\n- MATCH,DIRECT # 注释")) {
            assertEquals(policy.prefix("REJECT") + "MATCH,DIRECT", rules(compose("proxies: []\n$rules").yaml))
        }
        assertEquals(policy.prefix("REJECT"), rules(compose("proxies: []\nrules: []").yaml))
    }

    @Test fun providersOrSpecialNodesAreNotAssumedToBeRemote() {
        val config = compose("proxies: [{name: Alias, type: direct}, {name: GLOBAL, type: http}]\nproxy-providers: {}\nrules: ['MATCH,DIRECT']")
        assertEquals(policy.prefix("REJECT") + "MATCH,DIRECT", rules(config.yaml))
    }

    @Test fun unsupportedYamlNeverSilentlyReplacesSubscriptionRules() {
        for (bad in listOf("rules: test", "rules: &x ['MATCH,DIRECT']", "rules: []\n'rules': []", "---\nrules: []\n---\nrules: []", "rules: [", "rules: [{name: unexpected}]")) {
            assertTrue(runCatching { compose("proxies: []\n$bad") }.isFailure)
        }
    }

    @Test fun noPolicyKeepsOriginalRulesAndDoesNotTurnOnUidLookup() {
        val yaml = "proxies: []\nrules: ['MATCH,DIRECT'] # 未改写"
        val result = ConfigComposer.compose(yaml, RuntimeOptions()).yaml
        assertTrue(result.contains(yaml))
        assertFalse(result.contains("find-process-mode:"))
        assertEquals(listOf("MATCH,DIRECT"), rules(result))
    }

    @Test fun whitelistOnlySelectedAppsUseProxyAndOthersDirect() {
        val whitelist = ResolvedAppRouting(
            mode = AppRoutingMode.WHITELIST,
            selected = listOf(AppRouteOwner("org.example.browser", 10123, AppRoute.PROXY)),
        )
        val config = ConfigComposer.compose(
            "proxies: [{name: Node, type: socks5, server: localhost, port: 1080}]\nrules:\n  - MATCH,DIRECT\n",
            RuntimeOptions(appRouting = whitelist),
        ).yaml
        val actual = rules(config)
        assertTrue(actual[0].startsWith("AND,((IN-TYPE,TUN),(NOT,((UID,1-4294967295)))"))
        assertEquals("UID,10123,Node", actual[1])
        assertEquals("UID,10123,REJECT", actual[2])
        assertEquals("AND,((IN-TYPE,TUN),(NETWORK,TCP)),DIRECT", actual[3])
        assertEquals("AND,((IN-TYPE,TUN),(NETWORK,UDP)),DIRECT", actual[4])
        assertEquals("MATCH,DIRECT", actual[5])
        assertTrue(config.contains("find-process-mode: strict"))
    }

    @Test fun whitelistWithNoSelectionDirectsAllTunTraffic() {
        val config = ConfigComposer.compose(
            "proxies: []\nrules: ['MATCH,DIRECT']",
            RuntimeOptions(appRouting = ResolvedAppRouting(mode = AppRoutingMode.WHITELIST)),
        ).yaml
        assertEquals(
            listOf(
                "AND,((IN-TYPE,TUN),(NETWORK,TCP)),DIRECT",
                "AND,((IN-TYPE,TUN),(NETWORK,UDP)),DIRECT",
                "MATCH,DIRECT",
            ),
            rules(config),
        )
    }

    @Test fun blacklistSelectedAppsAreDirectAndOthersFollowSubscription() {
        val blacklist = ResolvedAppRouting(
            mode = AppRoutingMode.BLACKLIST,
            selected = listOf(AppRouteOwner("org.example.direct", 10124, AppRoute.DIRECT)),
        )
        val config = ConfigComposer.compose(
            "proxies: [{name: Node, type: socks5, server: localhost, port: 1080}]\nrules:\n  - MATCH,Node\n",
            RuntimeOptions(appRouting = blacklist),
        ).yaml
        val actual = rules(config)
        assertEquals("UID,10124,DIRECT", actual[0])
        assertEquals("MATCH,Node", actual[1])
        assertEquals(2, actual.size)
    }

    @Test fun reinstallSharedUidAndNewOwnerDoNotInheritPermission() {
        val original = AppRoutingPolicy.App(
            pkg = "org.example.app",
            label = "示例",
            uid = 10123,
            installedAt = 100,
            shared = emptyList(),
            system = false,
            fromRoot = false,
            icon = null,
        )
        val stored = mapOf(original.identity to AppRoute.DIRECT)
        assertEquals(10123, AppRoutingPolicy.resolveOwners(stored, listOf(original)).single().uid)
        for (modified in listOf(original.copy(pkg = "org.example.new"), original.copy(installedAt = 200), original.copy(shared = listOf("org.example.shared")))) {
            assertTrue(AppRoutingPolicy.resolveOwners(stored, listOf(modified)).isEmpty())
        }
        assertTrue(runCatching { ConfigComposer.directConfig(opts.copy(enableTun = false)) }.isFailure)
    }
}
