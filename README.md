To setup 
install the hardware and the SEM-METER app on your phone
To setup MQTT, go to settings (upper right), Home Assistant, then click User Your Own Server  then fill in IP of mqtt server (i.e.  mqtt://IP_of_PC_w_docker_and_broker    port:1883)
I skipped passwords but up to you

Setup the broker per the pdf instructions

See that the broker sees items from the Fusion Energy device by opening a PowerShell and run:  docker exec -it mosquitto mosquitto_sub -h localhost -t "#" -v

Look for something like
SEMMETER/94A9900AD2F2/HA {"sense":[[1233,0,0,0,0],[1233,0,0,0,0],[1233,16,2016,105,0],[1233,16,1987,105,0],[1233,12,270,1318,0],
[1233,150,14771,374,0],[1233,57,5707,305,0],[1233,0,658,45,0],[1233,18,1275,159,0],[1233,15,1092,53,0],[1233,16,1535,80,0],[1233,0,0,8,0],[1233,25,1921,317,0],
[1233,0,391,22,0],[1233,96,11403,697,0],[1233,0,1055,129,0],[1233,0,0,0,0],[1233,380,40090,2352,0],[1233,600,67824,4898,0]]}

You need the **SEMMETER/94A9900AD2F2/HA**   to fill in as the TOPIC in the device preferences


Install the driver code in Hubitat
Currently the devices are hard coded so you will have to change the following to match your devices
                def labelMap = [
                    0:"Furnace", 1:"Stove", 2:"Main Bath/LR", 3:"AC",
                    4:"Dishwasher", 5:"Den/DR/Sink Lights", 6:"Foyer/Kitchen Lights", 7:"Kitchen Outlets",
                    8:"Master/Blue Beds", 9:"Washer", 10:"RecRm/Low Deck",
                    11:"Microwave", 12:"Family Rm", 13:"Dryer",
                    14:"Fridge/Floor Outlets", 15:"Laundry/Garage", 16:"NA",
                    17:"MainA", 18:"MainB"
                ]


Create virtual device with type being the Fusion Energy MQTT

Fill in the topic and IP under preferences and save

Click Initialize and the states should start populating

You can create a dashboard and display the WATTS_Summary
