


The summary of CallerSensitive on the java side is generated based on
[Tai-e](https://github.com/pascal-lab/Tai-e),
Please refer to [Tai-e](https://github.com/pascal-lab/Tai-e)'s homepage for specific introduction
and installation.

Tai-e has its own taint analysis, so we use its taint analysis to generate callersensitive summary.
At the same time, Tai-e has its own configuration file. You can configure sources, sinks, and
transfer methods. Transfer methods are similar to SVF's extern functions, and the configuration
content is the side-effect of the method. for example,

```
sources:
  - { method: "<Main: java.lang.String source()>", type: "java.lang.String" }

sinks:
  - { method: "<Main: void sink(java.lang.String)>", index: 0 }

transfers:
  - { method: "<java.lang.String: java.lang.String concat(java.lang.String)>", from: 0, to: result, type: "java.lang.String" }
```
Transfer method **public String concat(String s)** is a Java string concatenate library function.
Its side effect is the value of the 0th parameter **(from: 0)**, which will be passed to the
return value **(to: result)**.

Next, we will use an example to illustrate the process of generating CallerSensitive summary,
```
public class Main {

    static {
        System.loadLibrary("nativeLibrary");
    }
    public native String nativeTransfer(String data1, String data2);

    String source() {
        return new String();
    }

    String link(String data){
        return data;
    }

    void sink(String s) {}

    public static void main(String[] args) {
        Main m = new Main();
        String taint = m.source();
        String s2 = m.link(taint);
        String data = s2;
        String s3 = m.nativeTransfer(s2, data);
        m.sink(s3);
    }
}
```

**public native String nativeTransfer(String data1, String data2)** is a native funtion.
Its function body is implemented on the C/C++side. The process of tainted data from source() to sink() is
```
source() -> link() -> nativeTransfer() -> sink()
```

The variables "taint" and "data" are aliased, so the generated CallerSensitive Summary needs to indicate that
both parameters of the nativeTranfer() function are tainted.

We run the following command in Tai-e:

```
-cp /the-path-of-Main.jar -m Main -java 8 -a pta=taint-config:/the-path-of-config-file;action:dump;only-app:true
```

The content of the configuration file is：

```aidl
sources:
  - { method: "<Main: java.lang.String source()>", type: "java.lang.String" }

sinks:
  - { method: "<Main: void sink(java.lang.String)>", index: 0 }
```

We get the following **CallerSensitiveSummary.json** file：
```
{
	"nativeMethodName":"nativeTransfer",
	"retType":"java.lang.String",
	"argsType":["java.lang.String","java.lang.String"],
	"taintedArgsPos":[0,1]
}
```

It contains the name of the nativeTransfer() function, the return type, the parameter type,
and which parameters are tainted.

[CallerSensitive-SVF](https://github.com/shuangxiangkan/CallerSensitive-SVF) uses the generated
**CallerSensitiveSummary.json** and the bc file compiled from the C++ source file
that implements nativeTransfer() to generate the corresponding summary, that is, the relationship
between parameters and returned values. The following is the **nativeCalleeSummary.yml** file generated
by CallerSensitive-SVF,
```
 - { method: "<Main: java.lang.String nativeTransfer(java.lang.String,java.lang.String)>", from: 1, to: result, type: "java.lang.String" }
 - { method: "<Main: java.lang.String nativeTransfer(java.lang.String,java.lang.String)>", from: 0, to: result, type: "java.lang.String" }
```
We merge the nativeCalleeSummary.yml file with the previous configuration file to get the complete
configuration file of Tai-e's taint analysis：

```aidl
sources:
  - { method: "<Main: java.lang.String source()>", type: "java.lang.String" }

sinks:
  - { method: "<Main: void sink(java.lang.String)>", index: 0 }

transfers:
  - { method: "<Main: java.lang.String nativeTransfer(java.lang.String,java.lang.String)>", from: 0, to: result, type: "java.lang.String" }
  - { method: "<Main: java.lang.String nativeTransfer(java.lang.String,java.lang.String)>", from: 1, to: result, type: "java.lang.String" }
```
The final taint analysis result of Tai-e is:

```aidl
Detected 1 taint flow(s):
TaintFlow{<Main: void main(java.lang.String[])>[2@L20] $r1 = invokevirtual $r0.<Main: java.lang.String source()>(); -> <Main: void main(java.lang.String[])>[5@L24] invokevirtual $r0.<Main: void sink(java.lang.String)>($r3);/0}
```
