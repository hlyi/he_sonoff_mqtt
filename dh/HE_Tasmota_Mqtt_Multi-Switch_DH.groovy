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


metadata {
	definition (name: "HE Tasmota Mqtt Multi-Switch DH", namespace: "hy", author: "H Yi") {
		capability "Actuator"
	}
}

preferences {
	input(name: "debugLogEnable", type: "bool", title: "Enable debug logging", defaultValue: false)
}


def parse(msg)
{
	if ( logEnable )
		log.debug "Parent called child parse" + msg
	def decoded = 0
	if ( msg[0] == "stat" && msg[1] =~ /^POWER\d+$/ ) {
		def matcher = msg[1] =~ /^(POWER)(\d+)$/ 
		if ( ! matcher ) {
			log.error "Unable to decode $msg[1]"
		}
		def cmd = matcher[0][1]
		def id = "${device.deviceNetworkId}-${matcher[0][2]}"
		def child = getChildDevice(id)
		if ( ! child ) {
			def dhtype = "Generic Component Switch"
			addChildDevice("hubitat", dhtype, id, [label: id, isComponent: true, name: dhtype])
			child = getChildDevice(id)
		}
		parent.parseSwitch(child,[msg[0], cmd, msg[2]])
	}
	if ( ! decoded && debugLogEnable ) {
		log.debug "Problem decoding " + msg + " for device ${device.deviceNetworkId}"
	}
}


def componentOn(dev)
{
	parent.componentOn(dev)
}


def componentOff(dev)
{
	parent.componentOff(dev)
}


def componentRefresh(dev)
{
	parent.componentRefresh(dev)
}


def installed()
{
	updated()
}


def updated()
{
	if (logEnable)
		log.info "updated() called"
	unschedule()
}

