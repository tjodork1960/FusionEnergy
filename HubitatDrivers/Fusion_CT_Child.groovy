metadata {
    definition(name: "Fusion CT Child", namespace: "your_namespace", author: "Tim") {
        capability "Power Meter"
        capability "Energy Meter"

        attribute "power", "number"
        attribute "energy", "number"
        attribute "burstCounter","number"
        attribute "lastUpdated", "string"
        attribute "stale", "bool"
        attribute "driverVersion", "string"
        attribute "debugEnable", "bool"  // track debug state
    }
}

def getDriverVersion() {
    return "v1.0.0"  // Update this string as needed
}

def installed() {
    log.info "CT Child installed: ${device.label}"
    sendEvent(name: "driverVersion", value: getDriverVersion())
}

def updated() {
    log.info "CT Child updated: ${device.label}"
    sendEvent(name: "driverVersion", value: getDriverVersion())
}

/* -------------------- PARENT UPDATES -------------------- */

def updatePower(BigDecimal value) {
    sendEvent(name: "power", value: value, unit: "Watts")
    if (device.currentValue("debugEnable")) log.debug "Power for ${device.label}: ${value} Watts"
    updateTimestamp()
}

def updateEnergy(BigDecimal value) {
    sendEvent(name: "energy", value: value, unit: "kWh")
    if (device.currentValue("debugEnable")) log.debug "Energy for ${device.label}: ${value} kWh"
    updateTimestamp()
}
def updateCounter(BigDecimal value) {
    sendEvent(name: "burstCounter", value: value)
    if (device.currentValue("debugEnable")) log.debug "burstCounter for ${device.label}: ${value} "
    updateTimestamp()
}


/* -------------------- TIMESTAMP & STALE LOGIC -------------------- */

def updateTimestamp() {
    def nowStr = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    sendEvent(name: "lastUpdated", value: nowStr)

    // Mark stale if last update > 5 minutes
    runIn(300, checkStale)
}

def checkStale() {
    def lastTsStr = device.currentValue("lastUpdated")
    if (!lastTsStr) return

    def lastTs = Date.parse("yyyy-MM-dd HH:mm:ss", lastTsStr)
    def diffSec = (new Date().time - lastTs.time) / 1000
    def isStale = diffSec > 300
    sendEvent(name: "stale", value: isStale)
}

/* -------------------- DEBUG LOGGING -------------------- */

def enableDebugLogging() {
    sendEvent(name: "debugEnable", value: true)
    log.info "Debug logging enabled for ${device.label}, will auto-disable in 5 minutes"
    runIn(300, disableDebugLogging)
}

def disableDebugLogging() {
    sendEvent(name: "debugEnable", value: false)
    log.info "Debug logging disabled for ${device.label}"
}
