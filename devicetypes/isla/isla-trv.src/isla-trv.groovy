metadata {
    definition (name: "isla TRV", namespace: "isla", author: "isla", mnmn: "isla", vid: "dc49a7d8-963b-3070-afde-91fc10360728", cstHandler: true) {
        capability "Sensor"
        capability "Actuator"
		capability "Configuration"
        capability "Health Check"
        
        capability "Switch"
        capability "Temperature Measurement"
        capability "Thermostat Heating Setpoint"
        capability "greenmedia29916.binaryBatteryState"

        fingerprint profileId: HA_PROFILE_ID, manufacturer: " _TZE200_c88teujp", model: "TS0601", deviceJoinName: "isla TRV"
    }

    preferences {
        defineParameterPreferences()
    }
}

// globals
private getHA_PROFILE_ID()                   { "0104" }
private getCLUSTER_ID()                      { "EF00" }

private getCOMMAND_SET_DATA()                { "00" }

private getCOMMAND_REPORT_INFO()             { "01" } //< device local change
private getCOMMAND_REPORT_DEVICE_STATUS()    { "02" } //< response to remote change
private getCOMMAND_SYNC_TIME()               { "24" }

private getDEGREES_C_TEXT()                  { "℃" }

private getParameterMap() {[
    [
        name: "Away Mode", key: "awayMode", type: DATA_TYPES.BOOL,
        dataPoint: DATA_POINTS.AWAY_MODE, defaultValue: false,
        description: "Overrides the heating temperature to 16${DEGREES_C_TEXT}, reducing energy consumption whilst you are away. "
                    + "NOTE: normal operation will not resume until this setting is disabled.",
        displayDuringSetup: false
    ],
    [
        name: "Window Detection", key: "windowDetection", type: DATA_TYPES.BOOL,
        dataPoint: DATA_POINTS.WINDOW_DETECTION, defaultValue: true,
        description: "Automatically closes the radiator valve when an open window is detected.",
        displayDuringSetup: false
    ],
    /*[
        name: "Anti-Freeze", key: "antiFreeze", type: DATA_TYPES.BOOL,
        dataPoint: DATA_POINTS.ANTI_FREEZE, defaultValue: true,
        description: "Automatically opens the radiator valve if the temperature falls below 5${DEGREES_C_TEXT}, closing again once 8${DEGREES_C_TEXT} is reached.",
        displayDuringSetup: false
    ],*/
    /*[
        name: "Anti-Scaling", key: "antiScaling", type: DATA_TYPES.BOOL,
        dataPoint: DATA_POINTS.ANTI_SCALING, defaultValue: true,
        description: "Anti-Scaling automatically opens the radiator valve for a short period if it remains cloased for two weeks. "
                    + "This helps prevent blockages from limescale buildup.",
        displayDuringSetup: false
    ],*/
    [
        name: "Child Lock", key: "childLock", type: DATA_TYPES.BOOL,
        dataPoint: DATA_POINTS.CHILD_LOCK, defaultValue: false,
        description: "Allows child lock to be enabled. With this setting active, child lock can be enabled by long pressing the rotary plate for over 5 seconds. "
                    + "When enabled the device will ignore any input and display \"LC\" untill disabled; to prevent tampering or accidental changes.",
        displayDuringSetup: false
    ],
    [
        name: "Temperature Offset", key: "tempOffset", type: DATA_TYPES.VALUE,
        dataPoint: DATA_POINTS.TEMP_OFFSET, defaultValue: 0, range: -6..6,
        description: "Due to the different mounting options and room sizes, the device's temperature reading may be out by a few ${DEGREES_C_TEXT}. "
                    + "If this is the case it can be offset with this option. The possible range is -6${DEGREES_C_TEXT} to 6${DEGREES_C_TEXT} inclusive.",
        displayDuringSetup: false
    ]
]}

def installed() {
    sendHubCommand(setDataPointCommandBool(DATA_POINTS.SCHEDULE_ENABLE, false))
    initAllPreferences()
}

// handle preference changes
def updated() {
    handlePreferenceChange()
}

def configure() {
    configureHealthCheck()
}

// parse events into attributes
def parse(String description) {
    def handled = false
    def event = zigbee.getEvent(description)

    def zigbeeMap = zigbee.parseDescriptionAsMap(description)
    if (zigbeeMap) {
        if (zigbeeMap.profileId == HA_PROFILE_ID) {
            if (zigbeeMap.clusterId == CLUSTER_ID) {
                handled = handleClusterMessage(zigbeeMap);
            }
        }
    }
}

// handle commands
def setHeatingSetpoint(value) {
    state.setpoint = value
    setDataPointCommandValue(DATA_POINTS.HEATING_SETPOINT, encodeTemp(value))
}

def on() {
    def cmds = []
    cmds += setDataPointCommandBool(DATA_POINTS.STATE, true)
    cmds += setDataPointCommandValue(DATA_POINTS.HEATING_SETPOINT, encodeTemp(state.setpoint))
    return cmds
}

def off() {
    setDataPointCommandBool(DATA_POINTS.STATE, false)
}

def ping() {

    /*
     * Doesn't actually refresh the switch on off status (returns an error), but will reliably check if the device is alive.
     *
     * The health check capability doesnt actually seam to call this function, and instead just calls zigbee.onOffRefresh(),
     * so lets call it here incase that changes.
     */
    return zigbee.onOffRefresh()
}

def configureHealthCheck() {
    Integer hcIntervalMinutes = 30
    sendEvent([name: "checkInterval", value: hcIntervalMinutes * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID]])
}

private handleClusterMessage(zigbeeMap) {
    switch (zigbeeMap.command) {
        case COMMAND_REPORT_INFO:
        case COMMAND_REPORT_DEVICE_STATUS:
            return handleReportInfo(zigbeeMap.data, zigbeeMap.command)
        case COMMAND_SYNC_TIME:
            sendHubCommand(timeSyncCommand())
            return true
        default:
            return false
    }
}

private handleReportInfo(payload, command) {
    def parsedCommand = parseReportInfo(payload)
    if (!parsedCommand) {
        log.warn "failed to parse report info payload: ${payload}"
        return false
    }
    if (handlePreferenceReport(parsedCommand, command)) {
        return true
    }
    switch (parsedCommand.dataPoint) {
        case DATA_POINTS.LOCAL_TEMP:
            if (parsedCommand.value != null) {
                def temp = decodeTemp(parsedCommand.value)
                log.debug "report temperature: ${temp}${DEGREES_C_TEXT}"
                sendEvent(name: "temperature", value: temp, unit: DEGREES_C_TEXT)
            } else {
                log.error "report local temp no value: ${parsedCommand}"
            }
            return true

        case DATA_POINTS.HEATING_SETPOINT:
            if (parsedCommand.value != null) {
                state.setpoint = decodeTemp(parsedCommand.value)
                log.debug "report heatingSetpoint: ${state.setpoint}${DEGREES_C_TEXT}"
                sendEvent(name: "heatingSetpoint", value: state.setpoint, unit: DEGREES_C_TEXT)
            } else {
                log.error "report heating setpoint no value: ${parsedCommand}"
            }
            return true

        case DATA_POINTS.STATE:
            if (parsedCommand.bool != null) {
                log.debug "report state: ${parsedCommand.bool ? "enabled" : "disabled"}"
                if (parsedCommand.bool) {
                       sendEvent(name: "switch", value: "on")
                } else {
                       sendEvent(name: "switch", value: "off")
                }
            } else {
                log.error "report state no bool: ${parsedCommand}"
            }
            return true

        case DATA_POINTS.BATTERY_LOW:
            if (parsedCommand.bitmap != null) {
                def batteryLowMap = decodeLowBatteryBitmap(parsedCommand.bitmap)
                if (batteryLowMap.batteryLow != null) {
                    if (batteryLowMap.batteryLow) {
                        log.debug "report batteryState: low"
                        sendEvent(name: "binaryBatteryState", value: "low")
                    } else {
                        log.debug "report batteryState: good"
                        sendEvent(name: "binaryBatteryState", value: "good")
                    }
                } else {
                    log.error "report battery bitmap no batteryLow: {command: ${parsedCommand}, map: ${batteryLowMap}}"
                }
            } else {
                log.error "report battery low no bitmap: ${parsedCommand}"
            }
            return true

        default:
            return false
    }
}

///////////////////////////////////

private getDATA_TYPES() { [
    BOOL:   "01", // [0/1]
    VALUE:  "02", // [ 4 byte value ]
    BITMAP: "05", // [ 1,2,4 bytes ] as bits
] }

private getDataTypeName(String dataType) {
    switch (dataType) {
        case DATA_TYPES.BOOL:
            return "BOOL"
        case DATA_TYPES.VALUE:
            return "VALUE"
        case DATA_TYPES.BITMAP:
            return "BITMAP"
        default:
            return "UNKNOWN Data Type: ${dataType}"
    }
}

private getDATA_POINTS() { [
    WINDOW_DETECTION:       "08",
    ANTI_FREEZE:            "0A",
    TEMP_OFFSET:            "1B",
    CHILD_LOCK:             "28",
    STATE:                  "65",
    LOCAL_TEMP:             "66",
    HEATING_SETPOINT:       "67",
    BATTERY_LOW:            "69",
    AWAY_MODE:              "6A",
    SCHEDULE_ENABLE:        "6C",
    ANTI_SCALING:           "82"
] }

private encodeTemp(temp) {
    return temp * 10
}

private decodeTemp(temp) {
    return temp / 10
}

private Integer decodeTempInt(temp) {
    return Math.floor(decodeTemp(temp))
}

private parseReportInfo(payload) {
    if (payload.size() < 7) {
        return null
    }
    def parsed = [
        status:        payload[0],
        transactionId: payload[1],
        dataPoint:     payload[2],
        dataType:      payload[3],
        data:          payload.subList(6, payload.size()),
        parsedData:    null,
        bool:          null,
        value:         null,
        bitmap:        null
    ]
    if (zigbee.convertHexToInt(payload[4] + payload[5]) != parsed.data.size()) {
        log.error "atempted to parse report command with data length mismatch: reported ${payload[4] + payload[5]}, actual ${parsed.data.size()}"
        return null
    }
    switch (parsed.dataType) {
         case DATA_TYPES.BOOL:
            parsed.bool = decodeBool(parsed.data)
            parsed.parsedData = parsed.bool
            break
        case DATA_TYPES.VALUE:
            parsed.value = decodeValue(parsed.data)
            parsed.parsedData = parsed.value
            break
        case DATA_TYPES.BITMAP:
            parsed.bitmap = decodeBitmap(parsed.data)
            parsed.parsedData = parsed.bitmap
            break
    }
    return parsed
}

private decodeBool(List data) {
    Boolean ret = null
    if (data.size() == 1) {
        switch (zigbee.convertHexToInt(data[0])) {
            case 0:
                ret = false
                break;
            case 1:
                ret = true
                break;
            default:
                log.error "atempted to parse report command with invalid BOOL data: invalid value (${data[0]})"
        }
    } else {
        log.error "atempted to parse report command with invalid BOOL data: invalid data length (${data})"
    }
    return ret
}

private decodeValue(List data) {
    Integer ret = null
    if (data.size() == 4) {
        long tmp = zigbee.convertHexToInt(data[0] + data[1] + data[2] + data[3])
        if (tmp & 0x80000000) {
            tmp -= 0x100000000
        }
        ret = tmp
    } else {
        log.error "atempted to parse report command with invalid VALUE data: invalid data length (${data})"
    }
    return ret
}

private decodeBitmap(List data) {
    Integer ret = null
    switch (data.size()) {
        case 1:
            ret = zigbee.convertHexToInt(data[0])
            break
        case 2:
            ret = zigbee.convertHexToInt(data[0] + data[1])
            break
        case 4:
            ret = zigbee.convertHexToInt(data[0] + data[1] + data[2] + data[3])
            break
        default:
            log.error "atempted to parse report command with invalid BITMAP data: invalid data length (${data})"
            break
    }
    return ret
}

private getNextTransactionId() {
    if (state.transactionId == null || state.transactionId >= 255) {
        state.transactionId = 0
    } else {
        state.transactionId = state.transactionId + 1
    }
    return state.transactionId
}

private setDataPointCommand(String dataType, String dataPoint, String data, String commandId = null) {
    if (commandId == null) {
        commandId = COMMAND_SET_DATA
    }
    def command = zigbee.command(
        CLUSTER_ID,
        commandId,
        "00" + zigbee.convertToHexString(getNextTransactionId(), 2) + dataPoint + dataType + zigbee.convertToHexString(data.size() / 2, 4) + data
    )
    return command
}

private setDataPointCommandBool(String dataPoint, Boolean data, String command = null) {
    def dataToSend = data ? 1 : 0
    return setDataPointCommand(DATA_TYPES.BOOL, dataPoint, zigbee.convertToHexString(dataToSend, 2), command)
}

private setDataPointCommandValue(String dataPoint, int data, String command = null) {
    return setDataPointCommand(DATA_TYPES.VALUE, dataPoint, convertSignedIntToHexString(data, 4), command)
}

private timeSyncCommand() {
    def utc = zigbee.convertToHexString(Math.floor(now() / 1000), 8)
    def local = utc

    def command = zigbee.command(
        CLUSTER_ID,
        zigbee.convertHexToInt(COMMAND_SYNC_TIME),
        "0008" + utc + local
    )
    return command
}

private getLOW_BATTERY_BITMAP_BITS() { [
    BATTERY_LOW: 0
] }

private Map decodeLowBatteryBitmap(int bitmap) {
    return [
        batteryLow: testBit(bitmap, LOW_BATTERY_BITMAP_BITS.BATTERY_LOW)
    ]
}

//////////////////
// preferences  //
//////////////////

private getSYNCED()          { "synced" }
private getSYNC_PENDING()    { "syncPending" }

private initAllPreferences() {
    state.currentPreferencesState = [:]
    parameterMap.each {
        initPreference(it)
    }
}

private initPreference(preference) {
    state.currentPreferencesState."$preference.key" = [:]
    state.currentPreferencesState."$preference.key".value = getPreferenceValue(preference)
    state.currentPreferencesState."$preference.key".status = SYNC_PENDING
}

private defineParameterPreferences() {
    parameterMap.each {
        input (title: it.name, description: it.description, type: "paragraph", element: "paragraph")

        switch(it.type) {
            case DATA_TYPES.BOOL:
                input(name: it.key, title: "", type: "bool", defaultValue: it.defaultValue, displayDuringSetup: it.displayDuringSetup, required: false)
                break
            case DATA_TYPES.VALUE:
                input(name: it.key, title: "", type: "number", defaultValue: it.defaultValue, /*range: it.range == null ? "*..*" : "${it.range}"*/, displayDuringSetup: it.displayDuringSetup, required: false)
                break
            default:
                log.warn "preference ${it.key} is of unhandled type: ${it.type}"
        }
    }
}

private handlePreferenceChange() {
    parameterMap.each {
        if (isPreferenceChanged(it)) {
            log.debug "Preference ${it.key} has been updated from value: ${state.currentPreferencesState."$it.key".value} to ${settings."$it.key"}"
            state.currentPreferencesState."$it.key".status = "syncPending"
        } else if (state.currentPreferencesState."$it.key".value == null) {
            log.warn "Preference ${it.key} no. ${it.parameterNumber} has no value."
        }
    }
    syncConfiguration()
}

private syncConfiguration() {
    def commands = []
    parameterMap.each {
        try {
            if (state.currentPreferencesState."$it.key".status == SYNC_PENDING) {
                switch (it.type) {
                    case DATA_TYPES.BOOL:
                        commands += setDataPointCommandBool(it.dataPoint, getCommandValue(it))
                        break
                    case DATA_TYPES.VALUE:
                        commands += setDataPointCommandValue(it.dataPoint, getCommandValue(it))
                        break
                    default:
                        log.warn "preference ${it.key} is of unhandled type: ${it.type}"
                }
            }
        } catch (e) {
            log.warn "There's been an issue with preference: ${it.key}"
            log.warn e
        }
    }
    sendHubCommand(commands)
}

private handlePreferenceReport(parsedCommand, command) {
    def preference = parameterMap.find( {it.dataPoint == parsedCommand.dataPoint} )
    if (preference == null) {
        return false
    }
    def key = preference.key
    def preferenceValue = getPreferenceValue(preference, settings."$key")
    if (preferenceValue == String.valueOf(parsedCommand.parsedData)) {
        log.debug "preference ${key} synced"
        state.currentPreferencesState."$key".value = preferenceValue
        state.currentPreferencesState."$key".status = "synced"
    } else {
        log.debug "preference ${key} syncPending, current value: ${String.valueOf(parsedCommand.parsedData)} settings value ${preferenceValue}"
        state.currentPreferencesState."$key"?.status = "syncPending"
        runIn(5, "syncConfiguration", [overwrite: true])
    }
    return true
}

private getPreferenceValue(preference, value = null) {
    if (value == null) {
        value = preference.defaultValue
    }
    switch (preference.type) {
        case DATA_TYPES.VALUE:
            value = constrainValueToRange(value, preference.range)
            // fall through
        case DATA_TYPES.BOOL:
            return String.valueOf(value)
        default:
            debug.warn "unsupported type for preference: ${it.key}"
    }
}

private getCommandValue(preference) {
    def settingsValue = settings."$preference.key"
    if (settingsValue == null) {
        settingsValue = preference.defaultValue
    }
    switch (preference.type) {
        case DATA_TYPES.BOOL:
            return settingsValue
        case DATA_TYPES.VALUE:
            settingsValue = constrainValueToRange(settingsValue, preference.range)
            return settingsValue
    }
}

private constrainValueToRange(int value, Range range) {
    if (range != null) {
        if (range.getFrom() > value) {
            value = range.getFrom()
        } else if (range.getTo() < value) {
            value = range.getTo()
        }
    }
    return value
}

private isPreferenceChanged(preference) {
    if (settings."$preference.key" != null) {
        return state.currentPreferencesState."$preference.key".value != getPreferenceValue(preference, settings."$preference.key")
    } else {
        return false
    }
}

///////////////
// utilities //
///////////////


private boolean testBit(int bitmap, int bit) {
    return getBit(bitmap, bit) != 0
}

private int getBit(int i, int bit) {
    return i & (1 << bit)
}

private convertSignedIntToHexString(int toConvert, int numBytes) {
    def result
    if (toConvert < -0x1000000) {
        log.error "convertSignedIntToHexString can only handle 24 bits for negative numbers, ie min it can convert is -0x1000000. got: ${toConvert}"
        return null
    }
    if (toConvert >= 0x80000000) {
        log.error "convertSignedIntToHexString can only handle 31 bits for positive numbers, ie max it can convert is 0x7FFFFFFF. got: ${toConvert}"
        return null
    }
    if (toConvert >= 0) {
        result = zigbee.convertToHexString(toConvert, numBytes * 2).toUpperCase()
    } else {
        // negative numbers
        long twosCompliment
        switch (numBytes) {
            case 0:
                return ""
            case 1:
                if (toConvert < -0x80) {
                    log.error "convertSignedIntToHexString with numBytes: ${numBytes}, min that can be converted is -0x80. got: ${toConvert}"
                    return null
                }
                twosCompliment = 0xFF + toConvert + 1
                break
            case 2:
                if (toConvert < -0x8000) {
                    log.error "convertSignedIntToHexString with numBytes: ${numBytes}, min that can be converted is -0x8000. got: ${toConvert}"
                    return null
                }
                twosCompliment = 0xFFFF + toConvert + 1
                break
            case 3:
                if (toConvert < -0x800000) {
                    log.error "convertSignedIntToHexString with numBytes: ${numBytes}, min that can be converted is -0x800000. got: ${toConvert}"
                    return null
                }
                // fall through
            default:
                twosCompliment = 0xFFFFFF + toConvert + 1
                break
        }
        if (numBytes < 4) {
            result = zigbee.convertToHexString(twosCompliment, numBytes * 2).toUpperCase()
        } else {
            result = ""
            for (int i = 0; i < numBytes && i < 3; i++) {
                def b = (twosCompliment >> (8 * i)) & 0xFF
                result = "${zigbee.convertToHexString(b, 2).toUpperCase()}${result}"
            }
            for (int i = 3; i < numBytes; i++) {
                result = "FF${result}"
            }
        }
    }
    return result     
}