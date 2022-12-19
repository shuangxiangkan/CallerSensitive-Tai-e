package pascal.taie.analysis.pta.plugin.taint;

import java.util.List;


public class nativeMethodTaintSummary {

    String nativeMethodName;

    String retType;
    List<String> argsType;

    List<Integer> taintedArgsPos;

    public String getRetType() {
        return retType;
    }

    public void setRetType(String retType) {
        this.retType = retType;
    }

    public String getNativeMethodName() {
        return nativeMethodName;
    }

    public void setNativeMethodName(String nativeMethodName) {
        this.nativeMethodName = nativeMethodName;
    }

    public List<String> getArgsType() {
        return argsType;
    }

    public void setArgsType(List<String> argsType) {
        this.argsType = argsType;
    }

    public List<Integer> getTaintedArgsPos() {
        return taintedArgsPos;
    }

    public void setTaintedArgsPos(List<Integer> taintedArgsPos) {
        this.taintedArgsPos = taintedArgsPos;
    }

}
