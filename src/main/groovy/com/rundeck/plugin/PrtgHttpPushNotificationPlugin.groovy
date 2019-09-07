import com.dtolabs.rundeck.plugins.notification.NotificationPlugin;

/**
 * This example is a minimal Notification plugin for Rundeck
 */

static class stateConverter {
    int toInt(String s){
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

    String toString(int status){
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


private class PrtgSensor {
    class PrtgChannel{
        String name;
        int value;
        String unit;

        public PrtgSensor(String name, int value, String unit){
            this.name = name
            this.value = value
            this.unit = unit
        }

        public toString(){
            if(unit == "rundeck")
                return "Channel \"" + name + "\": " + stateConverter.toString(value)
            else
                return "Channel \"${name}\": ${value$} in ${unit}"
        }

        //TODO
        public toXml(){

            if (unit == "rundeck")
                return """
                <result>
                    <channel>${name}</channel>
                    <value>${value}</value>
                    <unit>Custom</unit>
                    <ValueLookup>custom.prtg.rundecknotification.state</ValueLookup>
                </result>
                """
            else
                return """
                <result>
                    <channel>${name}</channel>
                    <value>${value}</value>
                    <unit>${unit}</unit>
                </result>
                """
        }
    }

    def overallState
    def runtime
    def avgRuntime
    def execId
    def jobId
    def nodeChannels = []
    
    public PrtgSensor(int jobId, int execId, int state, int runtime, int avgRuntime){
        this.jobId = jobId
        this.execId = jobId
        overallState = state
        this.runtime = runtime
        this.avgRuntime = avgRuntime
    }

    public void addNodeChannel(String name, int value){
        nodeChannels << new PrtgChannel(name, value, "rundeck")
    }

    // TODO
    private String genMessage(){
        return ""
    }

    public toXml(){
        def result = "<prtg>"
        result += (new PrtgChannel("Overall State", overallState, "rundeck")).toXml()
        result += (new PrtgChannel("Runtime", runtime, "TimeSeconds")).toXml()
        result += (new PrtgChannel("Average Runtime", avgRuntime, "TimeSeconds")).toXml()
        result += (new PrtgChannel("Execution ID", execId, "Custom")).toXml()
        nodeChannels.each {
            result += it.toXml()
        }
        result += "<text>" + genMessage() + "</text>"
        result += "</prtg>"
        return result
    }

}


rundeckPlugin(NotificationPlugin){
    onfailure {
        println("failure: data ${execution}")
        return true
    }

    onsuccess {
        println("success: data ${execution}")
        return true
    }
}