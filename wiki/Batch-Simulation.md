The following is an example of a batch file:

```
00:00:00.00>SCEN Heading-Normal-UA-Inst648-Rep1
00:00:00.00>PCALL Heading-Normal-UA-Inst648-Rep1.scn
00:00:00.00>PCALL areaDefiniton.scn
00:00:00.00>PCALL settingsOFF.scn
03:00:00.00>HOLD 

00:00:00.00>SCEN Heading-Normal-UA-Inst648-Rep2
00:00:00.00>PCALL Heading-Normal-UA-Inst648-Rep2.scn
00:00:00.00>PCALL areaDefiniton.scn
00:00:00.00>PCALL settingsOFF.scn
03:00:00.00>HOLD 
```

This batch file will run these two scenarios. It may be started through the command:

```
batch batch-file.scn
```

