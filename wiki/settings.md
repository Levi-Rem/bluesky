# The settings module
All global settings in BlueSky are stored in the settings module. They are constructed at startup from the `settings.cfg` file in your bluesky root folder (when you are running the source code), or in the bluesky folder in your home directory (when you are running the packaged version of BlueSky).

To ensure that configuration variables are present in the settings module, even if they are not defined in your `settings.cfg`, you should provide a default value for your variable using the `set_variable_defaults()` function, see below.

## Settings functions:

### set_variable_defaults()
Call this function at the top of your module or plugin, right after the `import` statements, to provide default values for the configuration variables you expect in the settings module.

**Example:**

```python
from bluesky import settings
settings.set_variable_defaults(var1=1.0, var2='a string', var3=[2, 3, 4])
...
# I use my settings here
some_function(settings.var1)
```

In this example, you provide default values for three new settings variables (`var1`, `var2`, and `var3`). Note that you can provide any number of variables in this way. Later in your module you use them as `settings.varn`, which returns the corresponding value from your `settings.cfg` if it is defined there, or otherwise the default value that you've specified.
