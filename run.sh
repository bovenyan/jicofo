git pull
rm ./dist/linux/jicofo-linux-x64-1.zip
ant rebuild dist.lin64
unzip -o dist/linux/jicofo-linux-x64-1.zip
pushd ./jicofo-linux-x64-1
./jicofo.sh --host=localhost --domain=boven-cute.dev.local --secret=TOh4XsSI --user_domain=auth.boven-cute.dev.local --user_name=focus --user_password=V6@YdRgb > Jicofo.log 2>&1
popd
