package com.zbproxy.android.proxy

import com.google.gson.annotations.SerializedName

/**
 * Root configuration model, compatible with ZBProxy JSON format.
 */
data class RootConfig(
    @SerializedName("Log") val log: LogConfig = LogConfig(),
    @SerializedName("Services") val services: MutableList<ServiceConfig> = mutableListOf(),
    @SerializedName("Router") val router: RouterConfig = RouterConfig(),
    @SerializedName("Outbounds") val outbounds: MutableList<OutboundConfig> = mutableListOf(),
    @SerializedName("Lists") val lists: MutableMap<String, MutableSet<String>> = mutableMapOf()
)

data class LogConfig(
    @SerializedName("Level") val level: String = "debug"
)

data class ServiceConfig(
    @SerializedName("Name") var name: String = "Hypixel-in",
    @SerializedName("TargetAddress") var targetAddress: String = "",
    @SerializedName("TargetPort") var targetPort: Int = 25565,
    @SerializedName("Listen") var listen: Int = 25565,
    @SerializedName("EnableProxyProtocol") var enableProxyProtocol: Boolean = false,
    @SerializedName("IPAccess") var ipAccess: IPAccessConfig? = null,
    @SerializedName("Minecraft") var minecraft: MinecraftServiceConfig? = null,
    @SerializedName("TLSSniffing") var tlsSniffing: TLSSniffingConfig? = null,
    @SerializedName("SocketOptions") var socketOptions: SocketOptionsConfig? = null
)

data class IPAccessConfig(
    @SerializedName("Mode") var mode: String = "",
    @SerializedName("ListTags") var listTags: List<String> = emptyList(),
    @SerializedName("LowerCase") var lowerCase: Boolean = false
)

data class MinecraftServiceConfig(
    @SerializedName("EnableHostnameRewrite") var enableHostnameRewrite: Boolean = true,
    @SerializedName("RewrittenHostname") var rewrittenHostname: String = "mc.hypixel.net",
    @SerializedName("OnlineCount") var onlineCount: OnlineCountConfig = OnlineCountConfig(),
    @SerializedName("IgnoreFMLSuffix") var ignoreFMLSuffix: Boolean = true,
    @SerializedName("IgnoreSRVRedirect") var ignoreSRVRedirect: Boolean = false,
    @SerializedName("HostnameAccess") var hostnameAccess: AccessConfig? = null,
    @SerializedName("NameAccess") var nameAccess: AccessConfig? = null,
    @SerializedName("PingMode") var pingMode: String = "",
    @SerializedName("MotdFavicon") var motdFavicon: String = "{DEFAULT_MOTD}",
    @SerializedName("MotdDescription") var motdDescription: String = "§d{NAME}§e, provided by §a§o{INFO}§r\\n§c§lProxy for §6§n{HOST}:{PORT}§r",
    @SerializedName("ScoreboardLines") var scoreboardLines: List<String> = emptyList()
)

data class OnlineCountConfig(
    @SerializedName("Max") var max: Int = 20,
    @SerializedName("Online") var online: Int = -1,
    @SerializedName("EnableMaxLimit") var enableMaxLimit: Boolean = false
)

data class AccessConfig(
    @SerializedName("Mode") var mode: String = "",
    @SerializedName("ListTags") var listTags: List<String> = emptyList(),
    @SerializedName("LowerCase") var lowerCase: Boolean = false
)

data class TLSSniffingConfig(
    @SerializedName("RejectNonTLS") var rejectNonTLS: Boolean = false,
    @SerializedName("RejectIfNonMatch") var rejectIfNonMatch: Boolean = false,
    @SerializedName("SNIAllowListTags") var sniAllowListTags: List<String> = emptyList()
)

data class SocketOptionsConfig(
    @SerializedName("KeepAlive") var keepAlive: Boolean = true,
    @SerializedName("KeepAlivePeriod") var keepAlivePeriod: Int = 0,
    @SerializedName("MultiPathTCP") var multiPathTCP: Boolean = false,
    @SerializedName("TCPFastOpen") var tcpFastOpen: Boolean = false,
    @SerializedName("TrafficClass") var trafficClass: Int = 0
)

data class RouterConfig(
    @SerializedName("Rules") val rules: MutableList<RuleConfig> = mutableListOf(),
    @SerializedName("DefaultOutbound") var defaultOutbound: String = "RESET"
)

data class RuleConfig(
    @SerializedName("Type") var type: String = "Always",
    @SerializedName("Parameter") var parameter: Any? = null,
    @SerializedName("Sniff") var sniff: List<String> = emptyList(),
    @SerializedName("Rewrite") var rewrite: RewriteConfig? = null,
    @SerializedName("Outbound") var outbound: String = ""
)

data class RewriteConfig(
    @SerializedName("Minecraft") var minecraft: MinecraftRewriteConfig? = null
)

data class MinecraftRewriteConfig(
    @SerializedName("Hostname") var hostname: String = "mc.hypixel.net",
    @SerializedName("Port") var port: Int = 25565
)

data class OutboundConfig(
    @SerializedName("Name") var name: String = "Hypixel-out",
    @SerializedName("TargetAddress") var targetAddress: String = "mc.hypixel.net",
    @SerializedName("TargetPort") var targetPort: Int = 25565,
    @SerializedName("Minecraft") var minecraft: MinecraftServiceConfig? = null,
    @SerializedName("SocketOptions") var socketOptions: SocketOptionsConfig? = null,
    @SerializedName("ProxyProtocolVersion") var proxyProtocolVersion: Int = 0,
    @SerializedName("ProxyOptions") var proxyOptions: ProxyOptionsConfig? = null
)

data class ProxyOptionsConfig(
    @SerializedName("Type") var type: String = "",
    @SerializedName("Network") var network: String = "",
    @SerializedName("Address") var address: String = ""
)