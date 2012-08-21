(ns ring.middleware.anti-forgery
  "Ring middleware to prevent CSRF attacks with an anti-forgery token."
  (:require [crypto.random :as random]))

(def ^:dynamic
  ^{:doc "Binding that stores a anti-forgery token that must be included
          in POST forms if the handler is wrapped in wrap-anti-forgery."}
  *anti-forgery-token*)

(defn- session-token [request token-gen-fn log-fn]
  (or (get-in request [:session :__anti-forgery-token])
      (token-gen-fn)))

(defn- assoc-in-session
  [response request k v]
  (if (contains? response :session)
    (update-in response [:session] merge {k v})
    (assoc-in response [:session] (merge (:session request) {k v}))))

(defn- assoc-session-token [response request token log-fn]
  (let [old-token (get-in request [:session :__anti-forgery-token])]
    (if (= old-token token)
      response
      (assoc-in-session response request :__anti-forgery-token token))))

(defn- form-params [request]
  (merge (:form-params request)
         (:multipart-params request)))

(defn- secure-eql? [^String a ^String b]
  (if (and a b (= (.length a) (.length b)))
    (zero? (reduce bit-or
                   (map bit-xor (.getBytes a) (.getBytes b))))
    false))

(defn- valid-request? [request token-gen-fn log-fn]
  (let [param-token  (-> request form-params (get "__anti-forgery-token"))
        stored-token (session-token request token-gen-fn log-fn)]
    (and param-token
         stored-token
         (secure-eql? param-token stored-token))))

(defn- post-request? [request]
  (= :post (:request-method request)))

(defn- access-denied [body]
  {:status 403
   :headers {"Content-Type" "text/html"}
   :body body})

(defn- default-token-generation-fn [] (random/base64 60))

(defn- default-error-callback
  [request]
  (access-denied "<h1>Invalid anti-forgery token</h1>"))

(defn wrap-anti-forgery
  "Middleware that prevents CSRF attacks. Any POST request to this handler must
  contain a '__anti-forgery-token' parameter equal to the last value of the
  *anti-request-forgery* var. If the token is missing or incorrect, an access-
  denied response is returned.
  The attack-callback is a function that is called when a post request does not
  include the expected security token. The function is passed the respective
  request and is expected to return an appropriate response."
  ([handler]
     (wrap-anti-forgery
      handler
      #{}
      default-token-generation-fn
      default-error-callback
      println))
  ([handler excluded-routes token-gen-fn attack-callback log-fn]
     (fn [request]
       (if (contains? excluded-routes (:uri request))
         (handler request)
         (binding [*anti-forgery-token* (session-token request token-gen-fn log-fn)]
           (if (and (post-request? request) (not (valid-request? request token-gen-fn log-fn)))
             (attack-callback request)
             (if-let [response (handler request)]
               (assoc-session-token response request *anti-forgery-token* log-fn))))))))
