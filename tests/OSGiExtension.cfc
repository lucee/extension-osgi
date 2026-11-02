component extends="org.lucee.cfml.test.LuceeTestCase" labels="osgi" {

	function beforeAll() {
		variables.service = getPageContext().getConfig().getFactory().getEngine().getBundleService();
	}

	function run( testResults, testBox ) {
		describe( "OSGi extension", function() {

			it( "provides a bundle service", function() {
				expect( service.isAvailable() ).toBeTrue();
			});

			it( "lists the installed bundles", function() {
				var bundles = service.listBundles();
				expect( arrayLen( bundles ) ).toBeGT( 0 );
				expect( bundles[ 1 ] ).toHaveKey( "name" );
				expect( bundles[ 1 ] ).toHaveKey( "version" );
				expect( bundles[ 1 ] ).toHaveKey( "state" );
			});

			it( "returns nothing for a bundle that does not exist", function() {
				expect( isNull( service.getBundleInfo( "does.not.exist", "1.0.0" ) ) ).toBeTrue();
			});

			it( "has no bundle for a class that is not from a bundle", function() {
				expect( isNull( service.getBundleInfo( createObject( "java", "java.lang.String" ).getClass() ) ) ).toBeTrue();
			});

		});
	}

}
