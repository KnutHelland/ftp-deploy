# ftp-deploy

A command line tool for easy deployment of your application to a
ftp-server. Can also watch your files and reflect your actions on the
server.

![Screenshot](doc/screenshot.png "Screenshot")

## Config file

In your project, create a config text-file like this:

```clojure
{:host "ftp://username:password@ftp.yourserver.com/www/"
 :directories [["local/directory/target/compiled-javascript" "js"]
 	       ["public-files/" "public"]]
 :exceptions ["*.DS_Store" "#*" ".#*" ".log"]
 :files [["/additional/file.txt" "with-another-name-online.txt"]]}
```

and save it as for example "production.ftp-profile"

**:host** should follow the pattern above.

**:directories** List of local directories to upload, and their destination
directories relative to path in **:host**

**:exceptions** List of exceptions in .gitignore style. (not operator (!) not
supported yet)

**:files** List of additional files to upload, and their filenames relative to
path in **:host**.


## Run

Create a executable with
```bash
lein uberjar
```

And use the executable with the command
```bash
java -jar ftp-deploy.jar production.ftp-profile --watch
```

(can of course omit the --watch)