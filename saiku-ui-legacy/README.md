# [Saiku UI](http://www.meteorite.bi)

[![saiku-view](https://raw.githubusercontent.com/OSBI/saiku/assets/saiku-demo-1.jpg)](https://try.meteorite.bi/)

A user interface for the analytical tool Saiku. <br />
For more information, see [Saiku](http://www.meteorite.bi).

> You can put the UI on a separate server (apache httpd or simply a webapp in tomcat/webapps e.g).

## Table of Contents
  1. [Setup](#setup)
     - [Build Instructions](#build-instructions)
     - [Run UI on Node.js proxy](#run-ui-on-nodejs-proxy)
     - [LiveReload Browser](#livereload-browser)
  3. [Wiki](#wiki)
  4. [Community](#community)
  5. [Bugs and Feature Requests](#bugs-and-feature-requests)
  6. [Discussion List](#discussion-list)
  7. [Browser Support](#browser-support)
  8. [Team](#team)
  9. [Contributing](#contributing)
  10. [History](#history)
  11. [License](#license)

## Setup

### Build Instructions

* Build using Maven

  - USAGE: mvn TASK1, TASK2, ...

  - Main Tasks:

    + clean: deletes all the build dirs
    + package: creates a .zip and .war (for dropping the UI in a java webapp environment) file in target/ that contains the Saiku UI
    + install: installs the .war file in local Maven repo (eg. ~/.m2)

### Run UI on Node.js proxy

In order to run it locally you'll need a basic server setup.

1. Install [NodeJS](https://nodejs.org/en/download/), if you don't have it yet.
2. Install local dependencies:

  ```sh
  npm install
  ```
3. You can simply test and run the UI on a NodeJS proxy server called [server.js](./server.js), that will utilize a remote backend as source.

  Just run the following command in your command line and then access the UI in
  the browser (by default, it will run at [http://localhost:8080](http://localhost:8080)):

  ```sh
  node server.js [port] [backend_host] [backend_port]
  ```
or
  ```sh
  npm start
  ```

4. To start the server in **HTTPS** mode, you will need generate a self-signed certificate, run the following commands in your shell:

  ```sh
  openssl genrsa -out key.pem
  openssl req -new -key key.pem -out csr.pem
  openssl x509 -req -days 9999 -in csr.pem -signkey key.pem -out cert.pem
  rm csr.pem
  ```
  ```sh
  node server.js https [port] [backend_host] [backend_port]
  ```

### LiveReload Browser

Install local dependencies:

```sh
npm install
```

Automatically reload your browser when files are modified. Enter command:

```sh
npm run dev
```
## Wiki

* [Saiku Wiki](http://saiku-documentation.readthedocs.io/en/latest/)

## Community

* [Saiku Community](http://community.meteorite.bi/)

## Bugs and Feature Requests

* [GitHub Issues](https://github.com/OSBI/saiku/issues/new)

## Discussion List

* [Saiku Dev Group](https://groups.google.com/a/saiku.meteorite.bi/forum/#!forum/dev)
* [Saiku User Group](https://groups.google.com/a/saiku.meteorite.bi/forum/#!forum/user)
* [Stack Overflow](http://stackoverflow.com/questions/tagged/saiku)
* [Freenode IRC - Channel: ##saiku](http://webchat.freenode.net/?randomnick=1&channels=%23%23saiku)

## Browser Support

We do care about it.

![Edge](https://raw.githubusercontent.com/alrra/browser-logos/master/src/edge/edge_48x48.png) | ![Chrome](https://raw.github.com/alrra/browser-logos/master/src/chrome/chrome_48x48.png) | ![Firefox](https://raw.github.com/alrra/browser-logos/master/src/firefox/firefox_48x48.png) | ![Opera](https://raw.github.com/alrra/browser-logos/master/src/opera/opera_48x48.png) | ![Safari](https://raw.github.com/alrra/browser-logos/master/src/safari/safari_48x48.png)
--- | --- | --- | --- | --- |
Latest ✔ | Latest ✔ | Latest ✔ | Latest ✔ | Latest ✔ |

## Team

[Saiku UI](http://www.meteorite.bi) is maintained by these people and a bunch of awesome [contributors](https://github.com/OSBI/saiku/graphs/contributors).

| [![Breno Polanski](https://avatars7.githubusercontent.com/u/1894191?v=4&s=70)](https://github.com/brenopolanski) | [![Bruno Catão](https://avatars4.githubusercontent.com/u/785116?v=4&s=70)](https://github.com/brunogamacatao) | [![Luis Garcia](https://avatars4.githubusercontent.com/u/2557898?v=4&s=70)](https://github.com/PeterFalken) | [![Mark Cahill](https://avatars5.githubusercontent.com/u/200365?v=4&s=70)](https://github.com/thinkjson) | [![Paul Stoellberger](https://avatars5.githubusercontent.com/u/454645?v=4&s=70)](https://github.com/pstoellberger) | [![Tom Barber](https://avatars6.githubusercontent.com/u/103544?v=4&s=70)](https://github.com/buggtb) |
|---|---|---|---|---|---|
| [Breno Polanski](https://github.com/brenopolanski) | [Bruno Catão](https://github.com/brunogamacatao) | [Luis Garcia](https://github.com/PeterFalken) | [Mark Cahill](https://github.com/thinkjson) | [Paul Stoellberger](https://github.com/pstoellberger) | [Tom Barber](https://github.com/buggtb) |

## Contributing

If you want to help, please read the [Contributing](../CONTRIBUTING.md) guide.

## History

For detailed changelog, check [Releases](https://github.com/OSBI/saiku/releases).

## License

Saiku and the Saiku UI are free software. The UI, contained in this repository,
is available under the terms of the Apache License Version 2. A copy is attached for your convenience.

**[⬆ back to top](#table-of-contents)**
