# Running Locally

This document describes how to set up [akvo-flow-services](https://github.com/akvo/akvo-flow-services) and [akvo-flow](https://github.com/akvo/akvo-flow/) so you can use akvo-flow-services in a completely local setting.

1. docker-compose up
2. You should now be able to generate reports in the akvo-flow dashboard.

### Testing

Testing is fastest through the REPL, as you avoid environment startup
time.

If you dont want to use the REPL, to run the unit tests:

```sh
docker-compose exec backend lein test
```

or the integration tests:

```sh
docker-compose exec backend lein test :integration
```

### Running Flow Services and Flow together locally

If you want to run both Flow and Flow Services locally and talking to each other, first look at Flow documentation counterpart, and then run:

```
docker-compose -f docker-compose.together.yml up --build -d
```