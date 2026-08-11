Within BlueSky, the different components communicate with each other over tcp/ip using the [ZMQ](https://github.com/zeromq) library. Messages sent over these connection consist of nested dicts, lists, and numpy arrays. These data are serialised using the [MSGPack](https://github.com/msgpack) library. If you want your application to communicate with BlueSky and it's written in Python, you can use BlueSky's [[Client]] class. An example of this is given below. In other languages you will have to make your own implementation.

# Example implementation: A simple text client
In this example we'll create a simple Qt window with an input line to enter stack commands that are sent to BlueSky, and a text box where echo lines coming from BlueSky are printed. A complete implementation can be found [here](https://github.com/TUDelft-CNS-ATM/bluesky/tree/master/extra/textclient).

This example consists of two parts: defining a Qt window with two text boxes, and initialising and starting the BlueSky Client. In this example we use Qt because communications to and from BlueSky are performed asynchronously, which is difficult to do in a plain text console.

## Step 1: Creating the gui
In this example we'll make a simple Qt window with two `QTextEdit` widgets; one to display incoming messages from BlueSky, and one that serves as input line to type stack commands that can be sent to BlueSky. The easiest implementation is to create two derived classes of `QTextEdit`; one for the incoming messages, and one for the command line. In the constructor we'll set some parameters relating to size, scrolling, and focus. The echo box will get a function to add text coming from BlueSky, and the command line will override Qt's keyPressEvent to catch Enter-presses and send data to BlueSky.

```python
from PyQt6.QtCore import Qt, QTimer
from PyQt6.QtWidgets import QApplication, QWidget, QVBoxLayout, QTextEdit, QLabel

import bluesky as bs
from bluesky.core import Base
from bluesky.network import subscriber
from bluesky.network.client import Client
from bluesky.stack import stack

class Echobox(QTextEdit, Base):
    ''' Text box to show echoed text coming from BlueSky. '''
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setMinimumHeight(150)
        self.setReadOnly(True)
```
We can use BlueSky's `@subscriber` decorator to connect functions and methods to incoming data. If not given explicitly, the function name is taken as network topic to subscribe to.
``` python
    @subscriber
    def echo(self, text, flags=None):
        ''' Add text to this echo box. '''
        self.append(text)
        self.verticalScrollBar().setValue(self.verticalScrollBar().maximum())

class InfoLine(QLabel, Base):
    @subscriber
    def acdata(self, data):
        ''' Example subscriber to aircraft state data '''
        self.setText(f"There are {len(data.lat)} aircraft in the simulation.")

class Cmdline(QTextEdit):
    ''' Wrapper class for the command line. '''
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setMaximumHeight(21)

    def keyPressEvent(self, event):
        ''' Handle Enter keypress to send a command to BlueSky. '''
        if event.key() == Qt.Key.Key_Enter or event.key() == Qt.Key.Key_Return:
```
We can use `bluesky.stack.stack()` to post stack commands. These can be processed directly by the client, or by the simulation under control of the client.
``` python
            stack(self.toPlainText())
            echobox.echo(self.toPlainText())
            self.setText('')
        else:
            super().keyPressEvent(event)
```

## Step 2: Initialising the Client
The simplest approach to connect to BlueSky is to use the Client class. Initialise a Client object, make sure that its update function is periodically called (in this example we use a `QTimer` for this), and use `Client.connect` to connect to the BlueSky server.

```python
if __name__ == '__main__':
    # Construct the Qt main object
    app = QApplication([])

    # Start the bluesky network client
    bs.init(mode='client')
    client = Client()
    network_timer = QTimer()
    network_timer.timeout.connect(client.update)
    network_timer.start(20)
    client.connect()

```
## Step 3: Putting it all together
What remains now is construction of our three custom objects, a Qt window, connecting to BlueSky, and starting the  Qt main loop.

``` python

    # Create a window with a stack text box and a command line
    win = QWidget()
    win.setWindowTitle('Example external client for BlueSky')
    layout = QVBoxLayout()
    win.setLayout(layout)

    echobox = Echobox(win)
    cmdline = Cmdline(win)
    infoline = InfoLine(win)
    layout.addWidget(echobox)
    layout.addWidget(cmdline)
    layout.addWidget(infoline)
    win.show()

    # Let echobox act as screen object
    # NOTE: this approach will soon be deprecated
    bs.scr = echobox

    # Start the Qt main loop
    app.exec()
```

Download a full version of this example [here](https://github.com/TUDelft-CNS-ATM/bluesky/tree/master/extra/textclient).