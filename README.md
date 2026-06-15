# Vote Max Service – Betting Offer Stake Storage and High Stakes List Service

## I. Project Overview

A simple HTTP service that provides stake storage and query functionality for different customers.  
Each customer first obtains a session key valid for 10 minutes, then can use that key to submit stakes for a betting offer.  
The service remembers the highest stake each customer has placed for each betting offer, and can return the top 20 highest stakes for a given betting offer (each customer appears at most once, taking the maximum stake).

The project uses no external frameworks, only the JDK's built-in `com.sun.net.httpserver.HttpServer`.  
There is no database or file persistence – all data is stored in memory and cleared when the service restarts.

## II. How to Run

### Requirements
- JDK 8 or higher (the code is adapted for JDK 8)

### Compile
Using PowerShell or cmd, navigate to the `vote-max` project root directory and run:
```
javac -encoding UTF-8 -d target\classes src\main\java\com\vote\max\VoteMaxServer.java
```

### Package
```
jar cfe vote-max-server.jar com.vote.max.VoteMaxServer -C target\classes com
```

### Run
```
java -jar vote-max-server.jar
```


## III. How to Verify

Keep the running CMD window with the program active. Open a new PowerShell or cmd window and navigate to the `vote-max` project root directory.

### Create a Session
For example, customer ID: 1234，cmd process command:
```
curl http://localhost:8001/1234/session
```

The output value is the unique session key.

### Place Stakes
Assume betting offer ID: 888. Replace `sessionkey` in the following commands with the session key obtained from the previous step.

First stake，cmd process command:
```
curl -X POST -d "4500" "http://localhost:8001/888/stake?sessionkey=xxxxx"
```
Second stake，cmd process command:
```
curl -X POST -d "2000" "http://localhost:8001/888/stake?sessionkey=xxxxx"
```
Third stake，cmd process command:
```
curl -X POST -d "500" "http://localhost:8001/888/stake?sessionkey=xxxxx"
```


### Test Highest Stake
Assuming betting offer ID: 888,cmd process command:
```
curl http://localhost:8001/888/highstakes
```

### Test Different Customers
Follow the "Create a Session" section again to generate a new session key for another customer, then repeat the "Place Stakes" steps and finally call "Test Highest Stake".

### Test Session Expiration
The default session validity is 10 minutes. Take any previously obtained session key, modify its value (or wait more than 10 minutes), then run:
```
curl -X POST -d "6000" "http://localhost:8001/888/stake?sessionkey=xxx"
```
If the session creation was more than 10 minutes ago, the command will return: `Invalid or expired session`
