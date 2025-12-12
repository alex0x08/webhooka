while true;
do
  
    status=$(curl -s -L -X GET -H 'X-Webhooka-Auth: MDIwEAYHKoZIzj0CAQYFK4EEAAYDHgAEOSnsUrEOhSWErFap2IRdrbOkSrPGww6WBk+aMw==' http://localhost:8086/webhooka/internal/api/hooks);
    printf "$(date +%H:%M:%S): $status \n";
  
    sleep 5
done
