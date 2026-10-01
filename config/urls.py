from django.conf import settings
from django.conf.urls.static import static
from django.contrib import admin
from django.urls import include, path

urlpatterns = [
    path("admin/", admin.site.urls),
    path("", include("suivi.urls")),
]

# Les photos de compteurs sont servies par Django en développement. Pour une
# mise en production derrière un vrai serveur web, c'est à lui de les servir.
if settings.DEBUG:
    urlpatterns += static(settings.MEDIA_URL, document_root=settings.MEDIA_ROOT)
