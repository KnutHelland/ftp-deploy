(ns helland.ftp-deploy.core
  (:gen-class :main true)
  (:require [clojure.string :as str]
            [clojure.data :as data]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clansi.core :as clansi])
  (:use [clojure.tools.cli :only [cli]])
  (:import [java.util Calendar]
           [java.net URL URLDecoder]
           [java.io File]
           [org.apache.commons.net.ftp FTP FTPClient FTPFile FTPReply]))

;;;
;;; Settings format:
;;;
;;; {:host "ftp://user:pass@ftp.server.com/destination/path"
;;;  :directories
;;;     [["directory/to/watch" "dest/on/server"]
;;;      ["another/dir" "dest/on/server"]]
;;;  :files [["../settings.php" "dest/on/server/settings.php"]]
;;;  :exceptions [".#*" "#*" "*.DS_Store"]}
;;;

;;;; Global init

(set! *warn-on-reflection* true)

(def global-ftp-connection
  "Global singleton ftp connection used in the application"
  (atom nil))


;;;; Debug funcs

(defn verboseln
  "Verbose debug info"
  [& strings]
  (apply println strings))

(defn verbose
  "Verbose debug info"
  [& strings]
  (apply print strings)
  (flush))


;;;; File system

(defn walk-dir
  "Walks recursively through a directory returns a list of all
   java.io.File objects."
  [dir]
  (file-seq (io/file dir)))

(defn get-dirs
  "Retrieves a list of dirnames from left to right."
  [file]
  (let [file (io/as-file file)]
   (loop [f (if (.isDirectory file) file (.getParentFile file))
          path '()]
     (if f
       (recur (.getParentFile f) (conj path (.getName f)))
       path))))


;;;; Git exception format

(defn gitexception->regex
  "Takes a git exception string and returns a regex"
  [exception]
  (re-pattern (str "^" (str/replace exception #"\*" "(.*?)") "$")))

(defn except?
  "Should the filename be excepted according to exceptions?"
  [filename exceptions]
  (not (nil? (some #(re-matches (gitexception->regex %) filename) exceptions))))


;;;; Path string manipulation

(defn concat-path
  "Concatenates a lot of path segments and concatenates them with
   slashes. Removes multiple slashes (//// -> /) and single dots /./
   Use this only with FTP-paths. Other paths are platform dependent."
  [& segments]
  (str/replace(subs (apply str (map (fn [seg]
                            (if-not (#{"."} seg)
                              (str "/" seg)
                              "")) segments)) 1) #"\/{2,}" "/"))

(defn ->dest-path
  "Parsing settings and path-segments and creates the destination
   path string"
  ^String [settings & path-segments]
  (let [dest (.getPath (io/as-url (:host settings)))]
    (apply (partial concat-path dest) path-segments)))


;;;; FTP Functions

(defn ftp-connect
  "Opens a connection to FTP. Returns the connection. Should be
   followed by (ftp-login client user pass)"
  [url]
  (let [^FTPClient client (FTPClient.)
        ^URL url (io/as-url url)]
    (verbose "Trying to connect to FTP")
    (.connect client
              (.getHost url)
              (if (= -1 (.getPort url))
                (.getDefaultPort url)
                (.getPort url)))
    (let [reply (.getReplyCode client)]
      (if-not (FTPReply/isPositiveCompletion reply)
        (do (.disconnect client)
            (verboseln (clansi/style " ERROR!" :red))
            nil)
        (do (verboseln (clansi/style " Success!" :green))
            client)))))

(defn ftp-try-connect
  "Tries n times to open a ftp connection. Four seconds between each
  time."
  [url n]
  (let [client (atom nil)
        i (atom 0)]
    (while (and (not @client) (< @i n))
      (when (> @i 0)
        (verboseln (clansi/style "Waiting 4 secs and trying again." :red))
        (Thread/sleep 4000))
      (reset! client (ftp-connect url))
      (swap! i inc))
    @client))

(defn ftp-login
  "Login the user and setups the connection. Returns boolean"
  [^FTPClient client user pass]
  (verbose "Logging in as" user)
  (if-not (.login client user pass)
    (do (verboseln (clansi/style " ERROR!" :red)) nil)
    (do (.setControlKeepAliveTimeout client 300)
        (.enterLocalPassiveMode client)
        (.setFileType client FTP/ASCII_FILE_TYPE)
        ;(.setBufferSize client 1024000)
        (verboseln (clansi/style " Success!" :green))
        true)))

(defn ftp-close
  "Close the connection"
  [^FTPClient client]
  (when (.isConnected client)
    (.disconnect client)))

(defn ftp-ensure-connection
  "Ensures that the client is a valid and connected FTP-client, or
  reconnects if not."
  [^FTPClient client url]
  (let [url (io/as-url url)]
    (if (or (not client)
            (not (.isConnected client)))
      (let [decode (fn [s] (URLDecoder/decode s "UTF-8"))
            user-info (.getUserInfo url)
            [user pass] (.split user-info ":" 2)]
        (if-let [client (ftp-try-connect url 5)]
          (if (ftp-login client (decode user) (decode pass))
            client
            nil)))
      client)))

(defn ftp-upload-file
  "Uploads the file or directory to the destination. Creates
  directories if necessary."
  [^FTPClient client ^File src ^String dest]
  (let [dirs (get-dirs dest)]
    (doall
     (for [dir dirs]
       (when-not (.changeWorkingDirectory client dir)
         (verbose "Creating directory" (clansi/style dir :cyan))
         (if (.makeDirectory client dir)
           (verboseln (clansi/style " Success!" :green))
           (verboseln (clansi/style " FAIL!" :red))))))
    (when (.isFile src)
      (verbose "Uploading file" (clansi/style (.getPath src) :cyan)
               "to" (clansi/style dest :cyan))
      (if (with-open [instream (java.io.FileInputStream. src)]
            (.storeFile ^FTPClient client ^String dest ^java.io.FileInputStream instream))
        (verboseln (clansi/style " Sucess!" :green))
        (verboseln (clansi/style " FAIL!" :red))))
    (when (and (.isDirectory src) (not (.changeWorkingDirectory client dest)))
      (verbose "Creating directory" (clansi/style dest :cyan))
      (if (.makeDirectory client dest)
        (verboseln (clansi/style " Success!" :green))
        (verboseln (clansi/style " FAIL!" :red))))))

(defn ftp-delete-file
  "Deletes the file/directory from FTP."
  [^FTPClient client ^String path]
  (if (.changeWorkingDirectory client path)
    (do
      (verbose (clansi/style "Deleting directory" :red) (clansi/style path :cyan))
      (if (.removeDirectory client path)
        (verboseln (clansi/style " Success!" :green))
        (verboseln (clansi/style " FAIL!" :red))))
    (do
      (verbose (clansi/style "Deleting file" :red) (clansi/style path :cyan))
      (if (.deleteFile client path)
        (verboseln (clansi/style " Success!" :green))
        (verboseln (clansi/style " FAIL!" :red))))))

(defn walk-files
  "Walks through all files matched by settings and calls callback with
   two args: (java.io.File) src and (string) dst."
  [callback settings]
  ;; :directories
  (dorun
   (for [dir (:directories settings)
         :let [dest (second dir)]
         ^File file (rest (walk-dir (first dir)))
         :let [dest-filename (subs (.getPath file) (count (first dir)))]
         :when (not (except? (.getName file) (:exceptions settings)))]
     (callback file (->dest-path settings dest dest-filename))))
  ;; :files
  (dorun
   (for [rules (:files settings)
         :let [^File file (io/file (first rules))
               dest (second rules)]
         :when (and (not (except? (.getName file) (:exceptions settings)))
                    (.isFile file))]
     (callback file (->dest-path settings dest)))))

(defn process-jobs!
  "Connects to FTP and uploads or deletes files. Jobs is
   a list of eighter [:upload src dest] [:delete target]"
  [settings jobs]
  (when (< 0 (count jobs))
    (let [client (ftp-ensure-connection @global-ftp-connection (:host settings))]
      (reset! global-ftp-connection client)
      (dorun (for [job jobs]
               (condp = (first job)
                 :upload (ftp-upload-file client (second job) (nth job 2))
                 :delete (ftp-delete-file client (second job))))))))

(defn run!
  "Runs the settings configuration"
  [settings]
  (let [client (ftp-ensure-connection @global-ftp-connection (:host settings))]
    (reset! global-ftp-connection client)
    (walk-files (partial ftp-upload-file client) settings)))

(defn watch!
  [settings]
  (let [last-check (atom 0)      ; timestamp when last checked. 0 first loop
        indexed-files (atom #{}) ; files we have handled last loop
        files (atom #{})         ; files which exists now (may differ from indexed)
        jobs (atom #{})]         ; tasks for (process-jobs!)
    (while true
      (do
        ;; Walk files:
        (walk-files
         (fn [^File file dest]
           (do (if (>= (.lastModified file) @last-check)
                 (swap! jobs conj [:upload file dest]))
               (swap! files conj [file dest])))
         settings)
        
        ;; Build job
        (let [diff (data/diff @files @indexed-files)]
          (when-not (= @last-check 0)
            (dorun
             (for [new-file (first diff)]
               (swap! jobs conj [:upload (first new-file) (second new-file)])))
            (dorun
             (for [removed-file (second diff)]
               (do
                 (swap! jobs conj [:delete (second removed-file)])
                 (swap! indexed-files #(remove #{removed-file} %)))))))

        ;; Run job
        (process-jobs! settings @jobs)

        ;; Prepare for next loop.
        (reset! indexed-files @files)
        (reset! files #{})
        (reset! jobs #{})
        (reset! last-check (- (System/currentTimeMillis) 1000))
        (Thread/sleep 1000)))))

(defn -main
  [config & args]
  (let [settings (edn/read-string (slurp config))]
    (if (= (first args) "--watch")
      (watch! settings)
      (run! settings))))

