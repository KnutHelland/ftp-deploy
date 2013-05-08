(ns helland.ftp-deploy.core
  (:gen-class :main true)
  (:require [clojure.string :as str]
            [clojure.data :as data]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:use [clojure.tools.cli :only [cli]])
  (:import [java.util Calendar]
           [java.net URL URLDecoder]
           [java.io File]
           [org.apache.commons.net.ftp FTP FTPClient FTPFile FTPReply]))

(set! *warn-on-reflection* true)

(def global-ftp-connection (atom nil))

(defn verboseln
  "Verbose debug info"
  [& strings]
  (apply println strings))

(defn verbose
  "Verbose debug info"
  [& strings]
  (apply print strings))

(defn walk-dir
  "Walks recursively through a directory returns a list of all
   java.io.File objects."
  [dir]
  (file-seq (io/file dir)))

(defn gitexception->regex
  "Takes a git exception string and returns a regex"
  [exception]
  (re-pattern (str "^" (str/replace exception #"\*" "(.*?)") "$")))

(defn except?
  "Should the filename be excepted according to exceptions?"
  [filename exceptions]
  (not (nil? (some #(re-matches (gitexception->regex %) filename) exceptions))))

(defn concat-path
  "Concatenates a lot of arguments into a string"
  [& segments]
  (subs (apply str (map (fn [seg]
                          (if-not (#{"."} seg)
                            (str "/" seg)
                            seg)) segments)) 1))

(defn get-dirs
  "Retrieves a list of dirnames from topmost level to right."
  [file]
  (let [file (io/file file)]
   (loop [f (if (.isDirectory file) file (.getParentFile file))
          path '()]
     (if f
       (recur (.getParentFile f) (conj path (.getName f)))
       path))))


;; -----------------------------------------------------------------------------
;; FTP Functions
;;

(defn ftp-connect
  "Opens a connection to FTP. Returns the connection. Should be
   followed by (ftp-login client user pass)"
  [url]
  (let [^FTPClient client (FTPClient.)
        ^URL url (io/as-url url)]
    (verbose "Trying to connect to FTP... ")
    (.connect client
              (.getHost url)
              (if (= -1 (.getPort url))
                (.getDefaultPort url)
                (.getPort url)))
    (let [reply (.getReplyCode client)]
      (if-not (FTPReply/isPositiveCompletion reply)
        (do (.disconnect client)
            (verboseln " REFUSED!")
            nil)
        (do (verboseln " Success!")
            client)))))

(defn ftp-try-connect
  "Tries n times to open a ftp connection. Four seconds between each
  time."
  [url n]
  (let [client (atom nil)
        i (atom 0)]
    (while (and (not @client) (< @i n))
      (when (> @i 0)
        (verboseln "Waiting 4 secs and trying again.")
        (Thread/sleep 4000))
      (reset! client (ftp-connect url))
      (swap! i inc))
    @client))

(defn ftp-login
  "Login the user and setups the connection. Returns boolean"
  [^FTPClient client user pass]
  (verbose "Logging in as" user)
  (if-not (.login client user pass)
    (do (verboseln " ERROR!") nil)
    (do (.setControlKeepAliveTimeout client 300)
        (.enterLocalPassiveMode client)
        (.setFileType client FTP/ASCII_FILE_TYPE)
        ;(.setBufferSize client 1024000)
        (verboseln " Success!")
        true)))

(defn ftp-close
  "Close the connection"
  [^FTPClient client]
  (when (.isConnected client)
    (.disconnect client)))

(defn ensure-connection
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

(defn upload-file
  "Uploads the file to the destination. Creates directories if necessary."
  [^FTPClient client ^File src ^String dest]
  (let [dest-file (io/file dest)
        dirs (get-dirs dest)]
    (doall
     (for [dir dirs]
       (when-not (.changeWorkingDirectory client dir)
         (verbose "Creating directory " dir)
         (if (.makeDirectory client dir)
           (verboseln " Success!")
           (verboseln " FAIL!")))))
    (when (.isFile src)
      (verbose "Uploading file " src " to dest: " dest-file)
      (if (with-open [instream (java.io.FileInputStream. src)]
            (.storeFile ^FTPClient client ^String dest ^java.io.FileInputStream instream))
        (verboseln " Sucess!")
        (verboseln " FAIL!")))))

(defn delete-file
  "Deletes the file from FTP."
  [^FTPClient client path]
  (verbose "Deleting file: " path)
  (if (.deleteFile client path)
    (verboseln " Sucess!")
    (verboseln " FAIL!")))


(defn walk-files
  "Walks through all files matched by settings and calls callback with
   two args: (java.io.File) src and (string) dst."
  [callback settings]
  (dorun
   (for [dir (:directories settings)
         :let [dest (second dir)]
         ^File file (rest (walk-dir (first dir)))
         :let [dest-filename (subs (.getPath file) (count (first dir)))]
         :when (not (except? (.getName file) (:exceptions settings)))]
     (callback file (concat-path (:dest settings) dest dest-filename))))
  (dorun
   (for [rules (:files settings)
         :let [^File file (io/file (first rules))
               dest (second rules)]
         :when (and (not (except? (.getName file) (:exceptions settings)))
                    (.isFile file))]
     (callback file (concat-path (:dest settings) dest)))))

(defn process-jobs!
  "Connects to FTP and uploads or deletes files. Jobs is
   a list of eighter [:upload src dest] [:delete target]"
  [settings jobs]
  (when (< 0 (count jobs))
    (if-not (= nil (:host settings))
      (let [client (ensure-connection @global-ftp-connection (:host settings))]
        (reset! global-ftp-connection client)
        (dorun (for [job jobs]
                 (condp = (first job)
                     :upload (upload-file client (second job) (nth job 2))
                     :delete (delete-file client (second job))))))
      (dorun (for [job jobs]
               (do (verbose "Job: " job)
                (condp = (first job)
                    :upload (verboseln "Should upload " (second job) " to " (nth job 1))
                    :delete (verboseln "Should delete " (second job)))))))))

(defn run!
  "Runs the settings configuration"
  [settings]
  (let [client (ensure-connection @global-ftp-connection (:host settings))]
    (reset! global-ftp-connection client)
    (walk-files (partial upload-file client) settings)))

(defn watch!
  [settings]
  (let [last-check (atom 0)      ; timestamp when last checked. 0 first loop
        indexed-files (atom #{}) ; files we have handled last loop
        files (atom #{})         ; files which exists now (may differ from indexed)
        jobs (atom [])]          ; tasks for (process-jobs!)
    (while true
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
      (reset! jobs [])
      (reset! last-check (- (System/currentTimeMillis) 1000))
      (Thread/sleep 1000))))

(defn -main
  [config & args]
  (let [settings (edn/read-string (slurp (str config ".clj")))]
    (if (= (first args) "--watch")
      (watch! settings)
      (run! settings))))

