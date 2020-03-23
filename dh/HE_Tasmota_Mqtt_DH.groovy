/*
 *
 *  Copyright 2019 H Yi
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *	  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *
 */

import groovy.json.JsonSlurper

metadata {
	definition (name: "HE Tasmota Mqtt DH", namespace: "hy", author: "H. Yi") {
		capability "Initialize"
		attribute "connection", ""
	}
}


preferences {
	input("ip", "text", title: "MQTT Broker IP Address", description: "IP Address of MQTT Broker that Tasmota devices connect to", required: true)
	input("port", "text", title: "MQTT Brober Port", description: "Port of MQTT Broker that Tasmota devices connect to", defaultValue: 1883, required: true)
	input("subinterval", "integer", title: "Subscribe update interval", description: "Refresh MQTT subscription in specified internval, 0-disable refresh", defaultValue: 3600, required: true)
	input("debugLogEnable", "bool", title: "Enable debug logging", defaultValue: false)
}


void installed()
{
	log.warn "installed..."
}


void parse(String description)
{
	def msg = interfaces.mqtt.parseMessage(description)
	if (debugLogEnable ) log.debug "Parsed Message:" + msg
	def matcher = msg.topic =~ /^(tele|cmnd|stat)\/(tasmota_([a-zA-Z0-9]+)_([0-9A-Fa-f]{12}))\/(.*)$/;
	def type = ""
	def id = ""
	def devType = ""
	def cmd = ""
	def topic = ""
	if ( matcher ) {
		type = matcher[0][1]
		topic = matcher[0][2]
		devType = matcher[0][3]
		id = matcher[0][4]
		cmd = matcher[0][5]
	}else{
		log.error "Unsupported topic " + msg.topic
		return
	}
	id = "${device.deviceNetworkId}_${id}"
	def child = getChildDevice(id)
	def namespace = "hubitat"
	def dhtype = "Tasmota Unknown"
	if ( ! child ) {
		if (debugLogEnable) log.debug "Adding child $id"
		def numSwitch = 1
		if ( devType == "sw" ) {
			dhtype = "Generic Component Switch"
		}else if ( devType == "msw" ){
			dhtype = "HE Tasmota Mqtt Multi-Switch DH"
			namespace = "hy"
		}
		if (dhtype == "Tasmota Unknown" ) {
			log.error "Unsupported device type ${devType}"
			return
		}
		addChildDevice(namespace, dhtype, id, [label: id, isComponent:false, name: dhtype] )
		log.info "Add child device ${id}"
		child = getChildDevice(id)
		state.devTopic[id] = topic
	}else {
		if ( !state.devTopic ){
			state.devTopic = [:]
		}
		if (! state.devTopic.containsKey(id) ) {
			state.devTopic[id] = topic
		}
	}
	dhtype = child.getTypeName()
	if ( dhtype == "HE Tasmota Mqtt Multi-Switch DH" ) {
		// custom processing
		child.parse( [type,cmd,msg.payload])
	}else{
		//local processing
		if ( dhtype == "Generic Component Switch" ){
			// handle generic component switch
			parseSwitch(child,[type,cmd,msg.payload])
		}else{
			log.error "Unspported device handler ${dhtype}"
		}
	}
}


void updated()
{
	log.info "updated..."
	initialize()
}


void uninstalled()
{
	log.info "disconnecting from mqtt"
	interfaces.mqtt.disconnect()
	sendEvent(name: "connection", value: "disconnected")
}


void initialize()
{
	try {
		def mqttInt = interfaces.mqtt
		//open connection
		mqttInt.connect("tcp://${ip}:${port}", "tasmotamqtt", null, null)
		//give it a chance to start
		log.info "connection established"
		sendEvent(name: "connection", value: "connected")
		pauseExecution(1000)
		mqttInt.subscribe("stat/#")
		mqttInt.subscribe("tele/#")
		if ( state.subinterval ) {
			state.mqtthdl = mqttInt;
			runIn ( state.subinterval, refreshSub )
		}
	} catch(e) {
		log.debug "initialize error: ${e.message}"
		state.mqtthdl = null
		reconnectMqtt()
	}
}


void mqttClientStatus(String message)
{
	log.info "Received status message ${message}"
}

def refreshSub()
{
	if ( state.mqtthdl ) {
		log.debug "Refresh subscription"
		state.mqtthdl.unsubscribe("stat/#")
		state.mqtthdl.unsubscribe("tele/#")
		state.mqtthdl.subscribe("stat/#")
		state.mqtthdl.subscribe("tele/#")
		if ( state.subinterval ) {
			runIn ( state.subinterval, refreshSub )
		}
	}
}

def parsePowerFromJson(dev,msg)
{
	def decoded = 0
	def jsonSlurper = new JsonSlurper()
	def st = jsonSlurper.parseText(msg)
	st.each { key, val ->
		if ( key =~ /^POWER\d*$/ ) {
			dev.sendEvent (name: "switch", value : (val == "OFF" ? "off" : "on"))
			log.debug "Update switch stat to $val"
			decoded = 1
		}
	}
	return decoded
}

def parseSwitch(dev, msg)
{
	def decoded = 0
	if ( msg[0] == "stat" && msg[1] =~ /^POWER\d*$/ ) {
		dev.sendEvent (name: "switch", value : (msg[2] == "OFF" ? "off" : "on"))
		log.info "Set switch to ${msg[2]}"
		decoded = 1
	}
	if ( msg[0] == "tele" && msg[1] == "STATE" ) {
		decoded = parsePowerFromJson(dev,msg[2])
	}
	if ( ! decoded && debugLogEnable ) {
		log.debug "Problem decoding ${msg} for device ${dev.deviceNetworkId}"
	}
}


def sendMqttCmd(networkId, cmd, arg)
{
	if ( ! state.devTopic.containsKey(networkId ) ){
		log.error "Unknown device ID ${networkId}, CMD = ${cmd}, ARG =${arg} ignored"
		return
	}
	def topic = state.devTopic[networkId]
	def pcmd = "cmnd/${topic}/${cmd}"
	if (debugLogEnable ) log.debug "publish ${pcmd} $arg"
	interfaces.mqtt.publish(pcmd, arg)
}


def reconnectMqtt()
{
	// first delay is 2 seconds, doubles every time
	state.reconnectDelay = (state.reconnectDelay ?: 1) * 2
	// don't let delay get too crazy, max it out at 10 minutes
	if(state.reconnectDelay > 600) state.reconnectDelay = 600
	runIn(state.reconnectDelay, initialize)
}


def componentOn(dev)
{
	swComponentOnOff(dev, "ON")
}


def componentOff(dev)
{
	swComponentOnOff(dev, "OFF")
}


def swComponentOnOff(dev, setOn)
{
	def cmd="POWER"
	def id = dev.deviceNetworkId;

	if ( id =~ /-\d{1,2}$/ ) {
		def matcher = id =~ /^(.*?)-(\d{1,2})$/
		if ( !matcher ) {
			log.error "Unable to parse Id ${device.deviceNetworkId}"
			return
		}
		id = matcher[0][1]
		cmd = "POWER${matcher[0][2]}"
	}
	sendMqttCmd(id, cmd, setOn )
}
