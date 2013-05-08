# ftp-deploy

A command line tool for easy deployment of your application to a
ftp-server. Can also watch your files and reflect your actions on the
server.

## Config file

In your project, create a config text-file like this:

```clojure
{:host "ftp://username:password@ftp.yourserver.com/"
 :dest "/www/"
 :directories [["local/directory/target/compiled-javascript" "js"]
 	       ["public-files/" "public"]]
 :files [["/additional/file.txt" "with-another-name-online.txt"]]}
```

and save it as for example "production.ftp-profile"


## Run

Create a executable with `lein uberjar`

And use the executable with the command `java -jar ftp-deploy.jar production.ftp-profile --watch`

(can of course omit the --watch)