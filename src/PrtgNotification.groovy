/*
   Copyright 2019 Cody Ernesti

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

*/

import com.dtolabs.rundeck.plugins.notification.NotificationPlugin
import com.dtolabs.rundeck.core.plugins.configuration.ValidationException
import groovy.transform.ToString
import java.util.concurrent.TimeUnit

// Globals
def LOGLEVEL = "INFO"

/*
    Logging Stuff
*/

def log(String l, def msg) {
    def m = ["DEBUG":1,"VERBOSE":2,"INFO":3,"WARN":4,"ERROR":5]

    if(m.get(l) >= m.get(LOGLEVEL)){
        println("PRTG NOTIFICATION " + l + " LOG: " + msg)
    }
}

/* 
    Class for providing standard methods
    to convert states to/from Rundeck and PRTG

    probably unnecessary after re-architecting
*/
class stateConverter {
    static int toInt(String s){
        switch(s){
            case "succeeded":
                return 1
            case "failed":
                return 2
            case "aborted":
                return 3
            case "running":
                return 4
            default:
                return 0 
        }
    }

    static String toString(int status){
        switch(status){
            case 1:
                return "succeeded"
            case 2:
                return "failed"
            case 3:
                return "aborted"
            case 4:
                return "running"
            default:
                return "unknown"
        }
    }
}

/*
    Class to provide a standard definition
    of a PRTG sensor with Rundeck details

    todo: look to see if I can just restructure
          this to easily export as XML
*/
@ToString(includeNames=true)
class PrtgSensor {

    /*
        Simple sub class to provide
        standard definition of channels
    */
    @ToString(includeNames=true)
    class PrtgChannel {
        String name;
        int value;
        String unit;

        public PrtgChannel(String name, def value, String unit){
            this.name = name
            this.value = value
            this.unit = unit
        }

        def toXml(){
            if (unit == "rundeck"){
                return """
                <result>
                    <channel>${name}</channel>
                    <value>${value}</value>
                    <unit>Custom</unit>
                    <ValueLookup>custom.prtg.rundecknotification.state</ValueLookup>
                </result>
                """
            }else{
                return """
                <result>
                    <channel>${name}</channel>
                    <value>${value}</value>
                    <unit>${unit}</unit>
                </result>
                """
            }
        }
    }

    def overallState
    def runtime
    def execId
    def nodeChannels
    def message
    
    public PrtgSensor(def execId, int state, long runtime){
        this.execId = execId
        overallState = state
        this.runtime = runtime
        nodeChannels = []
        message = ""
    }

    /*
        Public method to add additional "nodes"
    */
    def addNodeChannel(String name, int value){
        nodeChannels << new PrtgChannel(name, value, "rundeck")
    }

    /*
        Generate the string between the "text" tags

        If this method isn't ran, the text is blank.
    */
    def genMessage(String user, String jobName, def abortedBy, String executionType){
        String taskmsg = (executionType == "user") ? "manual task by ${user}" : "scheduled task"
        switch(overallState){
            case 1: message = "'${jobName}' ran successfully as a ${taskmsg}"
                    break
            case 2: message = "'${jobName}' failed when ran as a ${taskmsg}"
                    break
            case 3: message = "'${jobName}' was aborted by ${abortedBy} when ran as a ${taskmsg}"
                    break
            case 4: message = "'${jobName}' is currently running as a ${taskmsg}"
                    break;
            default: message = "'${jobName}' is in an unknown state when ran as a ${taskmsg}"
        }
    }

    /*
        Output all the sensor data as PRTG compatible XML
    */
    def toXml(){
        def result = "<prtg>"
        result += (new PrtgChannel("Overall State", overallState, "rundeck")).toXml()
        result += (new PrtgChannel("Runtime", runtime, "TimeSeconds")).toXml()
        result += (new PrtgChannel("Execution ID", execId, "Custom")).toXml()
        nodeChannels.each {
            result += it.toXml()
        }
        result += "<text>" + message + "</text>"
        result += "</prtg>"
        return result
    }
}

/*
    Build sensor xml and send it to PRTG using Rundeck Data
*/
def handleTrigger(execution, config){
    // define loglevel
    LOGLEVEL = execution.loglevel

    log("DEBUG","Execution Map - ${execution}")
    log("DEBUG","Config Map - ${config}")

    // define sensor
    def p

    try{
        // calc runtime in seconds
        long runtime = TimeUnit.MILLISECONDS.toSeconds(execution.dateEndedUnixtime - execution.dateStartedUnixtime)
        log("VERBOSE","Runtime calculated to ${runtime}")

        log("INFO","Gathering and formatting sensor information")
        p = new PrtgSensor(execution.id, stateConverter.toInt(execution.status), runtime)
        log("DEBUG", "Created new sensor ${this}")
        p.genMessage(execution.context.job.username, execution.job.name, execution.abortedby, execution.context.job.executionType)
        log("DEBUG", "Generated the following message - $p.message")

        // only add nodes if specified by job
        if(config.level == "node"){
            log("DEBUG", "Adding node channels")
            execution.failedNodeList.each {
                log("DEBUG", "Adding failed node ${it}")
                p.addNodeChannel(it.toString(), 2)
            }
            execution.succeededNodeList.each {
                log("DEBUG", "Adding succeeded node ${it}")
                p.addNodeChannel(it.toString(), 1)
            }
        }
    }catch(Exception ex){
        log("ERROR", "Error building sensor - " + ex.getMessage())
        return false
    }
    
    log("DEBUG","Final prtgSensor object - " + p.toString())
    log("DEBUG","XML for post - " + p.toXml())

    // Build URL
    def proto = (config.protocolOverride != null) ? config.protocolOverride : config.protocol
    def server = (config.hostOverride != null) ? config.hostOverride : config.host
    def port = (config.portOverride != null) ? config.portOverride : config.port
    def url = "${proto}://${server}:${port}/$config.token"
    log("VERBOSE", "Sending data to ${url}")

    // POST to PRTG
    try{
        def post = new URL(url).openConnection();
        post.setRequestMethod("POST")
        post.setDoOutput(true)
        post.setRequestProperty("Content-Type", "application/xml")
        post.getOutputStream().write(p.toXml().getBytes("UTF-8"));
        def postRC = post.getResponseCode();
        log("INFO", "PRTG replied with return code: " + postRC);

        if(postRC.equals(200)) {
            log("VERBOSE", post.getInputStream().getText());
            return true
        }

        return false
    }catch(Exception ex){
        log("ERROR", "Error sending post to PRTG- " + ex.getMessage())
        return false
    }
}

rundeckPlugin(NotificationPlugin){
    title="PRTG"
    description="Sends POST to PRTG via the HTTP Push Data Advanced Sensor"
    version="v0.0.1"
    url="https://github.com/SoarinFerret/rundeck-prtg-notification-plugin"
    author="Cody Ernesti"

    configuration{
        // Token
        token title:"Identification Token (Required)", description:"ID token for the HTTP Push Data Advanced Sensor", required:true, scope: "InstanceOnly"

        // Monitoring Level
        level title:"Monitoring Level (Required)", description:"Use Node to see each node value, or Job to just see overall job status", values: ["node","job"], required:true, scope:"InstanceOnly"

        // PRTG Server
        host title:"PRTG Server Address", description:"IP address or hostname referencing PRTG server", required:true, scope:"Project"
        hostOverride title:"PRTG Server Address Override", description:"IP address or hostname referencing PRTG server", required:false, scope:"InstanceOnly"

        // Protocol
        protocol title:"Protocol", values: ["https","http"], required:true, defaultValue:"https", scope:"Project"
        protocolOverride title:"Protocol Override", values: ["https","http"], required:false,scope:"InstanceOnly"

        // Port
        port title:"Port", description:"Port to use (typically 5050 for HTTP and 5051 for HTTPS)", required:true, defaultValue: 5051, type: 'Integer', scope:"Project"
        portOverride title:"Port Override", description:"Port to use (typically 5050 for HTTP and 5051 for HTTPS)", required:false, type: 'Integer', scope:"InstanceOnly"
    }

    onfailure { Map execution, Map config ->
        return handleTrigger(execution, config)
    }

    onsuccess { Map execution, Map config ->
        return handleTrigger(execution, config)
    }

    // don't support these currently
    // perhaps set sensor to warning with appropriate message??
    // leaving prints for now to be helpful
    onretryablefailure{ Map execution, Map config ->
        println "PRTG NOTIFICATION WARN LOG: PRTG's HTTP Push Sensor doesn't really support status updates, so lets ignore this"
        println "Job ${execution.job.name} failed but will be retried."
        return true
    }

    onavgduration{ Map execution, Map config ->
        println "PRTG NOTIFICATION WARN LOG: PRTG's HTTP Push Sensor doesn't really support status updates, so lets ignore this"
        println "Job ${execution.job.name} exceeded Average Duration!"
        return true
    }

    onstart{ Map execution, Map config ->
        println "PRTG NOTIFICATION WARN LOG: PRTG's HTTP Push Sensor doesn't really support status updates, so lets ignore this"
        println "Job ${execution.job.name} has been started by ${execution.user}..."
        return true //handleTrigger(execution, config)
    }
}