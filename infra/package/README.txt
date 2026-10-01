The standalone database cache is set to 128M. To lower it, edit
nis/db.properties.

Build and archive the runtime package from the infra directory:

  ./package.prepare.sh [mainnet|testnet]
  ./package.pack.sh

The network defaults to mainnet. The archive is written to the infra
directory and includes the package folder.

On Linux, start the node with ./nix.runNis.sh. On Windows, start it with
runNis.bat. Both launchers use the package's nis directory regardless of
the current working directory and run Java with -Xms4G -Xmx6G. Adjust those
memory switches in the launcher if needed.
