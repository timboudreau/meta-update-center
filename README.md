Meta Update Server
------------------

A simple standalone update server for [NetBeans](http://netbeans.org) and 
[NetBeans Platform](http://platform.netbeans.org) plugins.  You can deploy
this server by running it with ``java -jar`` and use it to serve plugins
and updates to existing plugins.

Download the latest build [from timboudreau.com](http://timboudreau.com/builds/job/meta-update-server/).

It serves NBM (NetBeans module) files with appropriate metadata so that the
NetBeans update center (Tools | Plugins) can download updated and new plugins from it.

You give it URLs where those NBM files live on the web, and it takes
care of automatically checking for updates.  The server can either direct callers
to those remote NBMs, or serve its own copies - this can be set on a file-by-file
basis.

Plugins are added to the server by adding remote URLs to an NBM file.  The
primary use-case is wanting to distribute some modules built by a continuous
build.

Once a plugin is added, its URL is polled once per-hour to check for updates
(this will use the ``If-Modified-Since`` HTTP header to avoid excessive
downloading).  If an update is found, it is downloaded;  if its specification
verion is greater than an existing copy on the server, the old one is replaced.

The primary use-case is continuous builds - in fact, it came out of a discussion
of writing a Jenkins/Hudson plugin for this purpose.  This project accomplishes
the same thing in a simpler way.  So, this is an update server which serves ``nbm``
files which are available elsewhere via HTTP.  It has a small HTTP web api which
lets you add new NBMs.

The server uses [Acteur](http://github.com/timboudreau/acteur), an asynchronous
server framework based on [Netty](http://netty.io).


Running The Server
------------------

Build it or download a binary.  Run it with ``java -jar``

If no directory path is passed to it, it will store and serve data from
``/tmp/nbmserver``

In a web browser, navigate to ``/`` to see a web page containing a list of
all modules served and an upload form (to upload, either pass ``--password``
on the command line, or make a note of the generated password logged on
startup.


Web API / Adding NBMs
---------------------

The server has an extremely simple web API:

 * An HTTP GET to ``/modules`` lists the modules being served in XML, using the
NetBeans autoupdate DTD
   * Add ``?json=true`` to get this data in JSON format instead, if you want
to get this data in a usable form for client-side Javascript
 * An HTTP GET/PUT/POST to ``/add`` with the parameter ``url`` set to the
remote URL of an NBM file
   * This call requires HTTP Basic authentication
 * An HTTP GET to ``/download/$CODE_NAME/$HASH.nbm`` will download the cached
copy of an NBM file


Configuring The Server
----------------------

All of the following properties can be set via the command-line by prefixing the 
property name with ``--`` - for example

    java -jar nbmserver-standalone.jar --port 3572 --external.port 80 --password hoohah

The system will also look for, and load if present, the following files:

 * /etc/nbmserver.properties
 * ~/nbmserver.properties
 * ./nbmserver.properties

any duplicates in the previous one being overridden in the next;  command-line
arguments override them all.

The following are useful properties

 * ``nbm.dir`` - where the files are stored
 * ``port`` - the port to run on
 * ``external.port`` - if you are running it behind a reverse proxy such 
as [NginX](http://nginx.org), this sets what port URLs to files this server is 
serving should have - the module catalogue it serves includes download URLs, 
which must be canonicalized.
 * ``password`` - the password used to authenticate the administrator (needed to
add URLs to the system).  If not set, a random one is generated on startup and logged.
 * ``nbm.dir`` - the path to the folder to store NBM files in.  Will be created if non-existent.  If not set,
uses ``/tmp/nbmserver`` or OS-specific equivalent.
 * ``hostname`` - the host name to use in external URLs in the module catalog
 * ``basepath`` - path to prepend to all URLs served and in the module catalog
 * ``workerThreads``, ``backgroundThreads`` - control the size of thread pools used for servicing events and background tasks
    * See note below
 * ``download.threads`` - how many concurrent downloads from remote update/build servers should be attempted simultaneously
    * See note below
 * ``poll.interval.minutes`` - the interval in minutes between checks of remote servers for newer 
versions of the NBMs served
 * ``admin.user.name`` - sets the user name expected for basic authentication for adding modules.  The default is ``admin``.

See the documentation for [Acteur](http://github.com/timboudreau/acteur) for 
additional settings.  The properties are loaded using [Giulius](http://github.com/timboudreau/giulius)
and follow its rules for what overrides what.

_Note:_ Since Netty and Acteur are asynchronous, threads are used much more efficiently - a single
thread can simultaneously service hundreds or thousands of connections.


Build and Run
-------------

Build with Maven and run the JAR.  Builds can be downloaded [here](http://timboudreau.com/builds).


Security & Reverse Proxy Setup
------------------------------

Calls which modify server state use HTTP Basic Authentication and require the
administrator user name and password (see configuration below).

Basic authentication is not secure unless you use HTTPs.  The standard way to
do that is to run the server behind a reverse proxy such as 
[NginX](http://nginx.org).

The following is an example NginX configuration which reverse-proxies the application
and redirects secured requests to HTTPS:

	location	 /modules {
		proxy_set_header	X-Real-IP	$remote_addr;
		proxy_set_header	X-Forwarded-For $proxy_add_x_forwarded_for;
		proxy_set_header	X-Forwarded-Host $host;
		proxy_set_header	Host $http_host;
		proxy_pass	http://localhost:8959;
		proxy_redirect	off;
	}
        location        /modules/add {
                if ($scheme = http) {
                        rewrite  ^/(.*)$  https://my.host.name/$1  permanent;
                }
                proxy_set_header        X-Real-IP       $remote_addr;
                proxy_set_header        X-Forwarded-For $proxy_add_x_forwarded_for;
                proxy_set_header        X-Forwarded-Host $host;
                proxy_set_header        Host $http_host;
                proxy_buffering         off;
                proxy_pass      http://localhost:8959;
                proxy_redirect  off;
        }

Replace ``my.host.name`` with the actual host name of the server.  To use this
configuration, you would want to start the server with ``--hostname my.host.name``
so that generated URLs will be correct.

Here is an example set of arguments for a [Debian](http://debian.org) launch
script:

```
DAEMON_ARGS="-jar 
    /home/tim/nbmserver-standalone.jar --password somepassword --basepath modules 
    --nbm.dir /var/nbmserver --port 8959 
    --hostname my.host.name --external.port 80 --external.secure.port=443"
```

The salient details of what this does:
 
 * Save and load NBM files using the folder ``/var/nbmserver``
 * Use port 8859
 * Use the hostname ``my.host.name`` in generated URLs in its module catalog
 * Set the ports used in generated URLs (we are behind a reverse proxy, so
we don't want to ask normal users to connet over port 8859)


Internals
---------

The server stores files in the folder specified by the property/argument ``nbm.dir``.  This is
laid out as follows:

 * nbm.dir
   * first.module.code.name.base
      * nbm-sha1-hash.json - JSON representation of the information in the module's ``Info/info.xml`` file, plus the original URL, download time, etc.
      * nbm-sha1-hash.nbm - the actual nbm file
   * second.module.code.name.base
      * ...

So, each module is stored in a directory with the module's code name as its name.
A version of a module is represented by a JSON file and an NBM file.  The name portion
of each file is the SHA-1 hash of the nbm file's bits.

The version of the module served is the one with the highest specification version in
its manifest.  If there is more than one build of the same version, the most recently
downloaded is used.

The server honors the ``If-Modified-Since`` and ``If-None-Match`` headers to reduce
server load.
