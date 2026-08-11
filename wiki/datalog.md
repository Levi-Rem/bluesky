# Data logging 

In BlueSky, you can define your own periodic loggers with the `CRELOG` command: 

    CRELOG MYLOG DT [Header]

This function creates a logger with name 'MYLOG' that, when enabled, will log data at a time interval 'dt'. If you specify a header, created logfiles (which are stored in the output directory specified in your BlueSky settings file) will start with this header on the first line. After calling `CRELOG`, you can add variables to the logger:

    MYLOG add [FROM parent] var1, ..., varn

The variables here are the variables as they are named in the BlueSky [[source|API Reference]]. You can look around these variables while running Bluesky with the `LSVAR` command. Without arguments, this command will list all the top-level objects that are accessible: [[traf|traffic]], [[sim|simulation]], and all of the loaded [[plugins|plugin]]. The member variables of these top-level objects are accessible directly, so these three lines would do the same:

    MYLOG ADD lat, lon, alt

or

    MYLOG ADD traf.lat, traf.lon, traf.alt

or

    MYLOG ADD FROM traf lat, lon, alt

BlueSky's top-level objects can also contain child objects with useful information, such as [[traf.perf|performance]], and [[traf.asas|asasapi]] (for performance data and CD&R data).

After creation, your logger can be enabled/disabled:

    MYLOG ON/OFF [dt]
